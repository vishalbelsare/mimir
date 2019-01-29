package mimir.algebra.gprom

import org.gprom.jdbc.jna._

import com.sun.jna.Memory
import com.sun.jna.Native

import mimir.algebra._
import mimir.ctables.CTables
import mimir.sql.sqlite.VGTermFunctions
import mimir.provenance.Provenance
import mimir.views.ViewAnnotation
import mimir.ctables.CTPercolator
import mimir.serialization.Json
import mimir.algebra.gprom.TranslationUtils._
import com.typesafe.scalalogging.slf4j.LazyLogging
import mimir.optimizer.OperatorOptimization
import mimir.optimizer.operator.ProjectRedundantColumns
import mimir.optimizer.operator.InlineProjections
import mimir.optimizer.operator.PushdownSelections
import mimir.optimizer.operator.PropagateEmptyViews
import mimir.optimizer.operator.PropagateConditions
import mimir.optimizer.operator.OptimizeExpressions
import mimir.optimizer.operator.PartitionUncertainJoins
import mimir.optimizer.operator.PullUpUnions
import mimir.optimizer.Optimizer

object ProjectionArgVisibility extends Enumeration {
   val Visible = Value("Visible")
   val Invisible = Value("Invisible") 
} 

object OperatorTranslation extends LazyLogging {
  
  var db: mimir.Database = null
  def apply(db: mimir.Database) = {
    this.db = db
  }
  
  def gpromStructureToMimirOperator(depth : Int, gpromStruct: GProMStructure, gpromParentStruct: GProMStructure ) : Operator = {
    (gpromStruct match {
      case list:GProMList => {
        val listHead = list.head
        gpromStructureToMimirOperator(depth, listHead, gpromParentStruct)
      }
      case listCell : GProMListCell => { 
        val listCellDataGPStructure = new GProMNode(listCell.data.ptr_value)
        val cnvNode = GProMWrapper.inst.castGProMNode(listCellDataGPStructure);
        val retOp = gpromStructureToMimirOperator(cnvNode.asInstanceOf[GProMQueryOperatorNode])
        retOp
      }
      case queryOpNode: GProMQueryOperatorNode => gpromStructureToMimirOperator(queryOpNode) 
    }) match {
      case proj@Project(projArgs, Annotate(op, invisSch)) => {
        Recover(Project(projArgs, proj), invisSch)
      }
      case x => x
    }
  }
  
  def gpromStructureToMimirOperator(gpromQueryOp: GProMQueryOperatorNode) : Operator = {
    val gpChildren = gpromListToScalaList(gpromQueryOp.op.inputs)
                      .map(input => input.asInstanceOf[GProMQueryOperatorNode])
    val mimirChildren = gpChildren.map(gpromStructureToMimirOperator)
    val mimirOpSchema = translateGProMSchemaToMimirSchema(gpromQueryOp)
    val taint = extractTaintFromGProMHashMap(gpromQueryOp.op.properties, gpChildren)
    val prov = extractProvFromGProMQueryOperatorNode(gpromQueryOp, mimirOpSchema, gpChildren)
    var dontAnnotate = false
    val mimirOp = gpromQueryOp match {
      case aggregationOperator : GProMAggregationOperator => { 
        val aggrs = gpromListToScalaList(aggregationOperator.aggrs).map(aggr => translateGProMExpressionToMimirExpression(gpChildren, aggr))
        val gb = gpromListToScalaList(aggregationOperator.groupBy).map(gbv => translateGProMExpressionToMimirExpression(gpChildren, gbv)) 
        val aggrsSch = gb match {
          case Seq() => mimirOpSchema
          case _ => mimirOpSchema.tail
        }
        val aggregates = aggrsSch.unzip._1.zip(aggrs).map(nameAggr => nameAggr._2 match { 
          case Function("DISTINCT", Seq(Function(name, args))) => AggFunction(name, true, args, nameAggr._1)
          case Function(name, args) => AggFunction(name, false, args, nameAggr._1)
        })
        
        //TODO: there appears to be a bug in gprom where it is adding some extra schema elements to a aggregate 
        //      this is a hack to work around it for now, but we need to get this fixed in gprom or figure out WTF
        //      -It is related to the todo below in the project case
        val aggrSchLen = gb.length + aggrs.length
        if(aggrSchLen != mimirOpSchema.length){
          Project(mimirOpSchema.map(_._1).zipWithIndex.foldLeft(Seq[ProjectArg]())((init, colIdx) => {
            if(colIdx._2 < aggrSchLen) init :+ ProjectArg(colIdx._1, Var(colIdx._1)) else init :+ ProjectArg(colIdx._1, gb.head.asInstanceOf[Var])
          }), Aggregate(gb.map(gbe => gbe.asInstanceOf[Var]), aggregates, mimirChildren.head))
        }
        else Aggregate(gb.map(gbe => gbe.asInstanceOf[Var]), aggregates, mimirChildren.head)
      }
      case constantRelationOperator : GProMConstRelOperator => { 
        val data = gpromListToScalaList(constantRelationOperator.values).map(row => gpromListToScalaList(row.asInstanceOf[GProMList]).map( cell => {
          translateGProMExpressionToMimirExpression(gpChildren, cell).asInstanceOf[PrimitiveValue]
        }))
        HardTable(mimirOpSchema, data)
      }
      case duplicateRemoval : GProMDuplicateRemoval => { 
        throw new Exception("Translation Not Yet Implemented '"+duplicateRemoval+"'") 
      }
      case joinOperator : GProMJoinOperator => { 
        joinOperator.cond match {
          case null => joinOperator.joinType match {
            //case GProM_JNA.GProMJoinType.GProM_JOIN_INNER => OperatorUtils.makeSafeJoin(mimirChildren.head, mimirChildren.tail.head)._1
            case GProM_JNA.GProMJoinType.GProM_JOIN_INNER => Join(mimirChildren.head, mimirChildren.tail.head)
            case _ => throw new Exception("Translation Not Yet Implemented '"+joinOperator+"'") 
          }
          case x => joinOperator.joinType match {
            //case GProM_JNA.GProMJoinType.GProM_JOIN_INNER => Select(translateGProMExpressionToMimirExpression(gpChildren, x), OperatorUtils.makeSafeJoin(mimirChildren.head, mimirChildren.tail.head)._1)
            //case GProM_JNA.GProMJoinType.GProM_JOIN_LEFT_OUTER => OperatorUtils.makeSafeLeftOuterJoin(mimirChildren.head, mimirChildren.tail.head, translateGProMExpressionToMimirExpression(gpChildren, x))._1
            case GProM_JNA.GProMJoinType.GProM_JOIN_INNER => Select(translateGProMExpressionToMimirExpression(gpChildren, x), Join(mimirChildren.head, mimirChildren.tail.head))
            case GProM_JNA.GProMJoinType.GProM_JOIN_LEFT_OUTER => LeftOuterJoin(mimirChildren.head, mimirChildren.tail.head, translateGProMExpressionToMimirExpression(gpChildren, x))
            case _ => throw new Exception("Translation Not Yet Implemented '"+joinOperator+"'") 
          }
        }
      }
      case nestingOperator : GProMNestingOperator => { 
        throw new Exception("Translation Not Yet Implemented '"+nestingOperator+"'") 
      }
      case orderOperator : GProMOrderOperator => { 
         Sort(gpromListToScalaList(orderOperator.orderExprs)
             .map(orderExpr => orderExpr.asInstanceOf[GProMOrderExpr])
             .map(orderExpr => (translateGProMExpressionToMimirExpression(gpChildren, orderExpr),orderExpr == 1))
             .map(orderExprDesc => SortColumn(orderExprDesc._1, orderExprDesc._2)), mimirChildren.head)
      }
      case projectionOperator : GProMProjectionOperator => {
        val projExpressions = gpromListToScalaList(projectionOperator.projExprs).map(projExpr => translateGProMExpressionToMimirExpression(gpChildren, projExpr))
        //TODO: there appears to be a bug in gprom where it is adding an extra schema element to a projection on top of an aggregate
        //      this is a hack to work around it for now, but we need to get this fixed in gprom
        val newProjExprsHack = if(mimirOpSchema.length != projExpressions.length){
          val badidx = mimirOpSchema.indexWhere(_._1.matches("MIMIR_COL_DET_MIMIR_COL_DET.+"))
          projExpressions.zipWithIndex.foldLeft(Seq[Expression]())((init, peIdx) => {
            if(peIdx._2 == badidx) init :+ IntPrimitive(1) :+ peIdx._1 else init :+ peIdx._1 
          })
        } else projExpressions
        Project(mimirOpSchema.unzip._1.zip(newProjExprsHack).map(nameExpr => ProjectArg(nameExpr._1,nameExpr._2)), mimirChildren.head)
      }
      case provenanceComputation : GProMProvenanceComputation => { 
        ProvenanceOf(mimirChildren.head) 
      }
      case selectionOperator : GProMSelectionOperator => { 
         val condition = translateGProMExpressionToMimirExpression(gpChildren, selectionOperator.cond)
         condition match {
            case Arithmetic(Arith.And,
                Comparison(Cmp.Gt, IntPrimitive(100000000), IntPrimitive(offset)), 
                Comparison(Cmp.Lt, IntPrimitive(-100000000), IntPrimitive(limitoff))) => {
              val limit = if((limitoff-offset) == -1) None else Some(limitoff-offset)    
              Limit(offset, limit, mimirChildren.head)  
            }
            case _ => {
              Select(condition, mimirChildren.head)
            }
         }
      }
      case setOperator : GProMSetOperator => { 
        if(setOperator.setOpType == GProM_JNA.GProMSetOpType.GProM_SETOP_UNION)
          Union(mimirChildren.head, mimirChildren.tail.head)
        else throw new Exception("Translation Not Yet Implemented '"+setOperator+"'") 
      }
      case tableAccessOperator : GProMTableAccessOperator => { 
        val tableSchema = mimirOpSchema.filterNot(sche => sche._1.equals("ROWID") || sche._1.equals(Provenance.rowidColnameBase))
        val tableOp = Table(tableAccessOperator.tableName, tableAccessOperator.tableName, tableSchema, Seq((Provenance.rowidColnameBase, Var("ROWID"), TRowId())) )
        tableOp
      }
      case x => throw new Exception("Translation Not Yet Implemented '"+x+"'")  
    }
    prov ++ taint match {
      case Seq() => mimirOp
      case provTaint if dontAnnotate => mimirOp
      case provTaint => {
       Project(mimirOpSchema.filter(schEl => provTaint.find(_._2.name.equals(schEl._1)) match {
          case Some(el) => false
          case None => true
        }).map(schEl => ProjectArg(schEl._1, Var(schEl._1))),
        Annotate(mimirOp, provTaint))
      }
    }
  }
  
  def translateGProMExpressionToMimirExpression(ctxOpers:Seq[GProMQueryOperatorNode], gpromExpr : GProMStructure) : Expression = {
    translateGProMExpressionToMimirExpression(ctxOpers, new GProMNode(gpromExpr.getPointer))
  }

  def translateGProMExpressionToMimirExpression(ctxOpers:Seq[GProMQueryOperatorNode], gpromExpr : GProMNode) : Expression = {
     val conditionNode = GProMWrapper.inst.castGProMNode(gpromExpr)
     conditionNode match {
       case operator : GProMOperator => {
         val expressions = gpromListToScalaList(operator.args).map(arg => new GProMNode(arg.getPointer))
         operator.name match {
            case "+" => new Arithmetic( Arith.Add, translateGProMExpressionToMimirExpression(ctxOpers, expressions(0)), translateGProMExpressionToMimirExpression(ctxOpers, expressions(1)))
            case "-" => new Arithmetic( Arith.Sub, translateGProMExpressionToMimirExpression(ctxOpers, expressions(0)), translateGProMExpressionToMimirExpression(ctxOpers, expressions(1)))
            case "*" => new Arithmetic( Arith.Mult, translateGProMExpressionToMimirExpression(ctxOpers, expressions(0)), translateGProMExpressionToMimirExpression(ctxOpers, expressions(1)))
            case "/" => new Arithmetic( Arith.Div, translateGProMExpressionToMimirExpression(ctxOpers, expressions(0)), translateGProMExpressionToMimirExpression(ctxOpers, expressions(1)))
            case "&" => new Arithmetic( Arith.BitAnd, translateGProMExpressionToMimirExpression(ctxOpers, expressions(0)), translateGProMExpressionToMimirExpression(ctxOpers, expressions(1)))
            case "|" => new Arithmetic( Arith.BitOr, translateGProMExpressionToMimirExpression(ctxOpers, expressions(0)), translateGProMExpressionToMimirExpression(ctxOpers, expressions(1)))
            case "AND" => new Arithmetic( Arith.And, translateGProMExpressionToMimirExpression(ctxOpers, expressions(0)), translateGProMExpressionToMimirExpression(ctxOpers, expressions(1)))
            case "OR" => new Arithmetic( Arith.Or, translateGProMExpressionToMimirExpression(ctxOpers, expressions(0)), translateGProMExpressionToMimirExpression(ctxOpers, expressions(1)))
            case "=" => new Comparison( Cmp.Eq , translateGProMExpressionToMimirExpression(ctxOpers, expressions(0)), translateGProMExpressionToMimirExpression(ctxOpers, expressions(1)))
            case "<>" => new Comparison( Cmp.Neq, translateGProMExpressionToMimirExpression(ctxOpers, expressions(0)), translateGProMExpressionToMimirExpression(ctxOpers, expressions(1)))
            case ">" => new Comparison( Cmp.Gt, translateGProMExpressionToMimirExpression(ctxOpers, expressions(0)), translateGProMExpressionToMimirExpression(ctxOpers, expressions(1))) 
            case ">=" => new Comparison( Cmp.Gte , translateGProMExpressionToMimirExpression(ctxOpers, expressions(0)), translateGProMExpressionToMimirExpression(ctxOpers, expressions(1)))
            case "<" => new Comparison( Cmp.Lt , translateGProMExpressionToMimirExpression(ctxOpers, expressions(0)), translateGProMExpressionToMimirExpression(ctxOpers, expressions(1)))
            case "<=" => new Comparison( Cmp.Lte , translateGProMExpressionToMimirExpression(ctxOpers, expressions(0)), translateGProMExpressionToMimirExpression(ctxOpers, expressions(1)))
            case "LIKE" => new Comparison( Cmp.Like , translateGProMExpressionToMimirExpression(ctxOpers, expressions(0)), translateGProMExpressionToMimirExpression(ctxOpers, expressions(1)))
            case "NOT LIKE" => new Comparison( Cmp.NotLike, translateGProMExpressionToMimirExpression(ctxOpers, expressions(0)), translateGProMExpressionToMimirExpression(ctxOpers, expressions(1)))
            case x => translateGProMExpressionToMimirExpression(ctxOpers, expressions(0));
        }    
       }
       case attributeReference : GProMAttributeReference => {
         val childSchemas = ctxOpers.map(oper => translateGProMSchemaToMimirSchema(oper))
         Var(childSchemas.flatMap(_.find( _._1.equals(attributeReference.name))) match {
          case Seq() => {
            //TODO: remove this hack for rowid added to projection above a table op (for gprom translation and spark backend)
            /*if(attributeReference.name.contains("ROWID"))
              childSchemas.flatMap(el => el.find(sel => sel._1.contains("ROWID")).headOption).head._1
            else*/ throw new Exception("Missing Attribute Reference: " + attributeReference.name + " : \n" + ctxOpers + s" \n ${childSchemas.map(_.mkString(",")).mkString("\n")}")
          }
          case x => x.head._1
        })
      }
      case constant : GProMConstant => {
      	if(constant.isNull == 1)
      	 new NullPrimitive()
      	else
      	  constant.constType match {
        	  case GProM_JNA.GProMDataType.GProM_DT_VARCHAR2 => new StringPrimitive(constant.value.getString(0))
            case GProM_JNA.GProMDataType.GProM_DT_BOOL => new BoolPrimitive(constant.value.getInt(0)==1)
            case GProM_JNA.GProMDataType.GProM_DT_FLOAT => new FloatPrimitive(constant.value.getFloat(0))
            case GProM_JNA.GProMDataType.GProM_DT_INT => new IntPrimitive(constant.value.getInt(0))
            case GProM_JNA.GProMDataType.GProM_DT_LONG => new IntPrimitive(constant.value.getLong(0))
            case GProM_JNA.GProMDataType.GProM_DT_STRING => new StringPrimitive(constant.value.getString(0))
            case _ => new NullPrimitive()
      	}
      }
      case caseExpr : GProMCaseExpr => {
      	val whenThenClauses = gpromListToScalaList(caseExpr.whenClauses).map(whenClause => whenClause match {
      	  case caseWhen : GProMCaseWhen => {
          	(translateGProMExpressionToMimirExpression(ctxOpers,caseWhen.when), translateGProMExpressionToMimirExpression(ctxOpers,caseWhen.then))
          }
      	  case x => throw new Exception("The GProM Expression should be a GProMCaseWhen")
      	}) 
        val elseClause = translateGProMExpressionToMimirExpression(ctxOpers, caseExpr.elseRes) 
        caseExpr.expr match {
      	  case null => {
      	    ExpressionUtils.makeCaseExpression(whenThenClauses.toList, elseClause)
      	  }
      	  case _ => {
      	    val testExpr = translateGProMExpressionToMimirExpression(ctxOpers, caseExpr.expr) 
      	    ExpressionUtils.makeCaseExpression(testExpr, whenThenClauses, elseClause)
      	  }
      	}
      }
      case caseWhen : GProMCaseWhen => {
      	throw new Exception("Something went wrong: this case should be handled above by: GProMCaseExpr: '"+caseWhen+"'")
      }
      case castExpr : GProMCastExpr => {
        val castArgs = translateGProMExpressionToMimirExpression(ctxOpers, castExpr.expr)
      	val fixedType =  TypePrimitive(getMimirTypeFromGProMDataType(castExpr.resultDT)) 
        Function("CAST", Seq(castArgs,fixedType)  )
      }
      case functionCall : GProMFunctionCall => {
        functionCall.functionname match {
          case "NOT" => {
            Not(translateGProMExpressionToMimirExpression(ctxOpers, new GProMNode(functionCall.args.head.data.ptr_value)))
          }
          case "sys_op_map_nonnull" => {
            val arg = translateGProMExpressionToMimirExpression(ctxOpers, new GProMNode(functionCall.args.head.data.ptr_value))
            Function("CAST", Seq(arg,TypePrimitive(TString())))
          }
          //TODO: remove this hack for rowid added to projection above a table op (for gprom translation and spark backend)
          case "monotonically_increasing_id" => {
            Var(Provenance.rowidColnameBase)
          }
          case "LEAST" => {
            Function("min", gpromListToScalaList(functionCall.args).map(arg => translateGProMExpressionToMimirExpression(ctxOpers, arg)))
          }
          case "GREATEST" => {
            Function("max", gpromListToScalaList(functionCall.args).map(arg => translateGProMExpressionToMimirExpression(ctxOpers, arg)))
          }
          case FN_UNCERT_WRAPPER => {
            translateGProMExpressionToMimirExpression(ctxOpers, new GProMNode(functionCall.args.head.data.ptr_value))
          }
          case CTables.FN_TEMP_ENCODED => {
            val fargs = gpromListToScalaList(functionCall.args).map(arg => {
              val mimirArg = translateGProMExpressionToMimirExpression(ctxOpers, arg)
              mimirArg match {
                case x => x
              }
            })
            val model = db.models.get(fargs(0).toString().replaceAll("'", ""))
            val idx = fargs(1).asInstanceOf[IntPrimitive].v.toInt;
            val vgtArgs =
              model.argTypes(idx).
                zipWithIndex.
                map( arg => fargs(arg._2+2))
            val vgtHints = 
              model.hintTypes(idx).
                zipWithIndex.
                map( arg => fargs(arg._2+vgtArgs.length+2))
            VGTerm(model.name, idx, vgtArgs, vgtHints)
          }
          case "CAST" => {
            val castArgs = gpromListToScalaList(functionCall.args).map( gpromParam => translateGProMExpressionToMimirExpression(ctxOpers, gpromParam))
          	val fixedType = castArgs.last match {
              case IntPrimitive(i) => TypePrimitive(Type.toSQLiteType(i.toInt))
              case TypePrimitive(t) => TypePrimitive(t)
              case x => x
            }
            Function("CAST", Seq(castArgs.head,fixedType)  )
          }
          case _ => {
            Function(functionCall.functionname, gpromListToScalaList(functionCall.args).map( gpromParam => translateGProMExpressionToMimirExpression(ctxOpers, gpromParam)))
          }
        }
      }
      case isNullExpr : GProMIsNullExpr => {
      	IsNullExpression(translateGProMExpressionToMimirExpression(ctxOpers, isNullExpr.expr))
      }
      case orderExpr : GProMOrderExpr => {
      	//TODO: fix Translation of GProM OrderExpr -> Mimir Expression to include asc/desc (SortColumn is not expression so not 1 to 1)
        //       for now this is handled in the case of orderoperator in operator translation
      	translateGProMExpressionToMimirExpression(ctxOpers, orderExpr.expr)
      }
      case rowNumExpr : GProMRowNumExpr => {
        ctxOpers.map(oper => extractProvFromGProMQueryOperatorNode(oper, translateGProMSchemaToMimirSchema(oper),gpromListToScalaList(oper.op.inputs).map(_.asInstanceOf[GProMQueryOperatorNode]))).flatten match {
          case Seq() => {
            //TODO: Fix this
            logger.debug(s"Error: no rowid in context: ${ctxOpers.mkString("\n----------------\n")}")
            Var(Provenance.rowidColnameBase)
            //throw new Exception(s"Error: no rowid in context: ${ctxOpers.map(op => translateGProMSchemaToMimirSchema(op).mkString(",")).mkString("\n")}")
          }
          case x => x.head._2.expr
        }
      }
      case sQLParameter : GProMSQLParameter => {
      	throw new Exception("Expression Translation Not Yet Implemented '"+sQLParameter+"'")
      }
      case windowBound : GProMWindowBound => {
      	throw new Exception("Expression Translation Not Yet Implemented '"+windowBound+"'")
      }
      case windowDef : GProMWindowDef => {
      	throw new Exception("Expression Translation Not Yet Implemented '"+windowDef+"'")
      }
      case windowFrame : GProMWindowFrame => {
      	throw new Exception("Expression Translation Not Yet Implemented '"+windowFrame+"'")
      }
      case windowFunction : GProMWindowFunction => {
      	throw new Exception("Expression Translation Not Yet Implemented '"+windowFunction+"'")
      }
      case keyValue : GProMKeyValue => {
        val key = translateGProMExpressionToMimirExpression(ctxOpers, keyValue.key)
        val value = translateGProMExpressionToMimirExpression(ctxOpers, keyValue.value)
        logger.debug(s"key: $key\nvalue:$value")
        value
      }
      case list:GProMList => {
        //TODO: Verify that anywhere that there is a list it is being handled properly
        val mimirExprs = gpromListToScalaList(list).map(expr => translateGProMExpressionToMimirExpression(ctxOpers, expr))
        mimirExprs(0)
      }
      case listCell : GProMListCell => { 
        translateGProMExpressionToMimirExpression(ctxOpers, new GProMNode(listCell.data.ptr_value))
      }
      case x => {
        throw new Exception("Expression Translation Not Yet Implemented '"+x+"'")
      }
     }
  }
  
  def extractTaintFromGProMHashMap(hashMapNode: GProMNode, ctxOpers:Seq[GProMQueryOperatorNode] ) : Seq[(String, AnnotateArg)] = {
    if(hashMapNode == null)
      Seq()
    else
      extractTaintFromGProMHashMap(GProMWrapper.inst.castGProMNode(hashMapNode).asInstanceOf[GProMHashMap], ctxOpers)  
  }
  
  def extractTaintFromGProMHashMap(hashMap: GProMHashMap, ctxOpers:Seq[GProMQueryOperatorNode] ) : Seq[(String, AnnotateArg)] = {
    if(hashMap == null)
      Seq()
    else{
      var scList = Seq[(String, AnnotateArg)]()
      val taintMapNode = GProMWrapper.inst.gpromGetMapString(hashMap.getPointer, "UNCERT_MAPPING")
      if(taintMapNode != null){
        val taintMap = taintMapNode.asInstanceOf[GProMHashMap]
        var mapElem = taintMap.elem
        while(mapElem != null){
          val key = new GProMNode(mapElem.key)
          val value = new GProMNode(mapElem.data)
          if(key == null || value == null)
            logger.error("WTF... there is some issue this should not be null")
          else{
            val annotateArg = 
            GProMWrapper.inst.castGProMNode(value) match {
              case keyValue : GProMKeyValue => (GProMWrapper.inst.castGProMNode(keyValue.key), GProMWrapper.inst.castGProMNode(keyValue.value)) match {
                case (srcAttrRef:GProMAttributeReference, taintAttrRef:GProMAttributeReference) => {
                  (srcAttrRef.name, AnnotateArg(ViewAnnotation.TAINT,taintAttrRef.name, getMimirTypeFromGProMDataType(taintAttrRef.attrType), Var(srcAttrRef.name)))
                }
                case x => throw new Exception("There is some issue. Taint should be attrRef but is:" + x)
              }
              case _ => throw new Exception("There is some issue. this should be a key-value")
            }
            scList = scList :+ annotateArg
          }
          if(mapElem.hh.next == null)
            mapElem = null
          else{
            val elemnext = new GProMHashElem(mapElem.hh.next)
            mapElem = new GProMHashElem.ByReference()
            mapElem.key = elemnext.key
            mapElem.data = elemnext.data
            mapElem.hh = elemnext.hh
          } 
        }
      }
      scList
    }
  }
  
  def extractProvFromGProMQueryOperatorNode(gpQOp:GProMQueryOperatorNode, opSchema:Seq[(String, Type)], ctxOpers:Seq[GProMQueryOperatorNode] ) : Seq[(String, AnnotateArg)] = {
    gpQOp.op.provAttrs match {
      case null => extractProvVarsFromAggPropHashmap(gpQOp.op.properties, opSchema, ctxOpers)
      case x => gpromIntPointerListToScalaList(x).map(attrIdx => {
        val mimirChildOpSchemas = ctxOpers.map(childOp => translateGProMSchemaToMimirSchema(childOp))
        val attr = opSchema(attrIdx)
        val (attrName, provExpr) = (attr._1, Var(attr._1))
        (attrName, AnnotateArg(ViewAnnotation.PROVENANCE, attrName, attr._2, provExpr)) 
      })
    }
  }
  
  def extractProvVarsFromAggPropHashmap(hashMapNode: GProMNode, opSchema:Seq[(String, Type)], ctxOpers:Seq[GProMQueryOperatorNode] ) : Seq[(String, AnnotateArg)] = {
    if(hashMapNode == null)
      Seq()
    else
      extractProvVarsFromAggPropHashmap(GProMWrapper.inst.castGProMNode(hashMapNode).asInstanceOf[GProMHashMap], opSchema, ctxOpers)  
  }
  
  def extractProvVarsFromAggPropHashmap(hashMap: GProMHashMap, opSchema:Seq[(String, Type)], ctxOpers:Seq[GProMQueryOperatorNode]) : Seq[(String, AnnotateArg)] = {
    val mimirChildOpSchemas = ctxOpers.map(childOp => translateGProMSchemaToMimirSchema(childOp))
    hashMap match {
      case null => Seq()
      case _ => (GProMWrapper.inst.gpromGetMapString(hashMap.getPointer, "USER_PROV_ATTRS") match{
        case null => Seq()
        case node => gpromListToScalaList(node.asInstanceOf[GProMList])
                    .map(li => translateGProMExpressionToMimirExpression(ctxOpers, li) match {
                      case StringPrimitive(provColName) => Var(provColName) //type not converting properly (ATTR Ref in gprom has string instead of int for COLLUMN_0) 
                      case x => throw new Exception("There is some issue. Prov should be String AttrRef but is:" + x)
                    }) 
      }).map(expr => {
        val attr = opSchema.find(_._1.equals(expr.name)) match {
          case Some(attrDef) => attrDef
          case None => {
            mimirChildOpSchemas.flatMap(schema => schema.find(_._1.equals(expr.name))) match {
              case Seq() => throw new Exception("Problem Extracting Prov:  Missing Attr Def: " + expr.name + " => " +  opSchema + " \n" + ctxOpers)
              case x => x.head
            }
          }
        }
        val (attrName, pexpr) = (attr._1, expr)
       (attrName, AnnotateArg(ViewAnnotation.PROVENANCE, attrName, attr._2, pexpr))         
      })
    }
  }
  
  def setGProMQueryOperatorParentsList(subject : GProMQueryOperatorNode, parent:GProMStructure) : Unit = {
    subject.op.parents = createGProMQueryOperatorParentsList(parent) 
    subject.write()
  }
  
  def buildGProMOp(typ:Int, inputs:Seq[GProMQueryOperatorNode], schema:GProMSchema.ByReference , parents:Seq[GProMQueryOperatorNode], provAttrs:Seq[Int], properties:GProMNode.ByReference) : GProMQueryOperator.ByValue = {
    new GProMQueryOperator.ByValue(typ, scalaListToGProMList(inputs), schema, scalaListToGProMList(parents), scalaListToGProMListInt(provAttrs), properties)		 
  }
  
  def mimirToGProM(f: Operator => GProMQueryOperatorNode)(targetOp:Operator) = {
    f(targetOp)
  }
  
  //legacy shortcut method
  def mimirOperatorToGProMList( mimirOperator :  Operator) :  GProMList.ByReference = {
    scalaListToGProMList(Seq(mimirOperatorToGProMOperator(mimirOperator)))
  }
  
  def mimirOperatorToGProMOperator( mimirOperator :  Operator, userProv:Option[(String,Expression,Type)] = None) : GProMQueryOperatorNode = {
    synchronized { 
      try{
    val gpChildren = mimirOperator.children.map(mimirOperatorToGProMOperator(_,userProv))
    val mimirOpSchema = db.typechecker.schemaOf(mimirOperator)
    mimirOperator match {
			case Project(cols, src) => {
  			 val toQoScm = translateMimirSchemaToGProMSchema("PROJECTION", mimirOpSchema)
  			 val gqo = buildGProMOp(GProM_JNA.GProMNodeTag.GProM_T_ProjectionOperator,gpChildren,toQoScm,Seq(),Seq(),null)
  			 val gProjOp = new GProMProjectionOperator.ByValue(gqo, scalaListToGProMList(cols.map(col => translateMimirExpressionToGProMStructure(mimirOperator.children, col.expression))))
  			 gpChildren.map(setGProMQueryOperatorParentsList(_, gProjOp))
  			 gProjOp
			 }
			case ProvenanceOf(psel) => {
			  val toQoScm = translateMimirSchemaToGProMSchema("PROVENANCE", mimirOpSchema)
			  val gqo = buildGProMOp(GProM_JNA.GProMNodeTag.GProM_T_ProvenanceComputation, gpChildren, toQoScm, Seq(),Seq(),null)
			  new GProMProvenanceComputation.ByValue(gqo, 0, 0, null, null)
			}
			case Annotate(subj,invisScm) => {
        throw new Exception("Operator Translation not implemented '"+mimirOperator+"'")
      }
			case Recover(subj,invisScm) => {
        throw new Exception("Operator Translation not implemented '"+mimirOperator+"'")
      }
			case Aggregate(groupBy, agggregates, source) => {
			  val transSchema = groupBy match {
			    case Seq() => mimirOpSchema
			    case _ => mimirOpSchema.tail :+ mimirOpSchema.head
			  }
			  val toQoScm = translateMimirSchemaToGProMSchema("AGG", transSchema)
        val gqoPropsNode = groupBy match {
			    case Seq() => null
			    case x => new GProMNode.ByReference(createDefaultGProMAggrPropertiesMap("AGG", groupBy.map(_.name)).getPointer)
			  }
			  val gpromAggrs = scalaListToGProMList(agggregates.map(aggr => 
			    if(aggr.distinct){
			      val gpromExprList = scalaListToGProMList(Seq(
		          translateMimirExpressionToGProMStructure(mimirOperator.children, 
    			      Function(aggr.function, aggr.args)) match {
        			    case gpFunc:GProMFunctionCall => {
        			      gpFunc.isAgg = 1
        			      gpFunc.write()
        			      gpFunc
        			    }
        			    case x => x
        			  }))
            val gpromFunc = new GProMFunctionCall.ByValue(GProM_JNA.GProMNodeTag.GProM_T_FunctionCall, "DISTINCT", gpromExprList, 1)
            gpromFunc
  			  }
  			  else{
  			    translateMimirExpressionToGProMStructure(mimirOperator.children, 
  			      Function(aggr.function, aggr.args)) match {
      			    case gpFunc:GProMFunctionCall => {
      			      gpFunc.isAgg = 1
      			      gpFunc.write()
      			      gpFunc
      			    }
      			    case x => x
      			  }
  			  }
			   ))
			  val gpromGroupBy = scalaListToGProMList(groupBy.map(groupByCol => translateMimirExpressionToGProMStructure(mimirOperator.children, groupByCol)))
        val gqo = buildGProMOp(GProM_JNA.GProMNodeTag.GProM_T_AggregationOperator, gpChildren, toQoScm, Seq(), Seq(), gqoPropsNode)
			  val aggOp = new GProMAggregationOperator.ByValue(gqo, gpromAggrs, gpromGroupBy)
			  gpChildren.map(setGProMQueryOperatorParentsList(_, aggOp))
			  aggOp
			}
			case Select(cond, Join(lhs, rhs)) => { 
			  val toQoScm = translateMimirSchemaToGProMSchema("JOIN", mimirOpSchema)
			  val gqo = buildGProMOp(GProM_JNA.GProMNodeTag.GProM_T_JoinOperator, gpChildren, toQoScm, Seq(), Seq(), null)
			  val gpjoinop = new GProMJoinOperator.ByValue(gqo, GProM_JNA.GProMJoinType.GProM_JOIN_INNER, new GProMNode.ByReference(translateMimirExpressionToGProMStructure(mimirOperator.children, cond).getPointer))
			  gpChildren.map(setGProMQueryOperatorParentsList(_, gpjoinop))
			  gpjoinop
			}
			case Select(cond, src) => {
			  val toQoScm = translateMimirSchemaToGProMSchema("SELECT", mimirOpSchema)
        val gqo = buildGProMOp(GProM_JNA.GProMNodeTag.GProM_T_SelectionOperator, gpChildren, toQoScm, Seq(), Seq(), null) 
        val gpselop = new GProMSelectionOperator.ByValue(gqo, new GProMNode.ByReference(translateMimirExpressionToGProMStructure(mimirOperator.children, cond).getPointer) )
			  gpChildren.map(setGProMQueryOperatorParentsList(_, gpselop))
			  gpselop
			}
			case LeftOuterJoin(lhs, rhs, condition) => {
			  val toQoScm = translateMimirSchemaToGProMSchema("JOIN", mimirOpSchema)
			  val gqo = buildGProMOp(GProM_JNA.GProMNodeTag.GProM_T_JoinOperator, gpChildren, toQoScm, Seq(), Seq(), null)
			  val gpjoinop = new GProMJoinOperator.ByValue(gqo, GProM_JNA.GProMJoinType.GProM_JOIN_LEFT_OUTER, new GProMNode.ByReference(translateMimirExpressionToGProMStructure(mimirOperator.children, condition).getPointer ))
			  gpChildren.map(setGProMQueryOperatorParentsList(_, gpjoinop))
			  gpjoinop
			}
			case Join(lhs, rhs) => {
			  val toQoScm = translateMimirSchemaToGProMSchema("JOIN", mimirOpSchema)
			  val gqo = buildGProMOp(GProM_JNA.GProMNodeTag.GProM_T_JoinOperator, gpChildren, toQoScm, Seq(), Seq(), null)
			  val gpjoinop = new GProMJoinOperator.ByValue(gqo, GProM_JNA.GProMJoinType.GProM_JOIN_INNER, new GProMNode.ByReference(translateMimirExpressionToGProMStructure(mimirOperator.children, BoolPrimitive(true)).getPointer))
			  gpChildren.map(setGProMQueryOperatorParentsList(_, gpjoinop))
			  gpjoinop
			}
			case Union(lhs, rhs) => {
			  val toQoScm = translateMimirSchemaToGProMSchema("UNION", mimirOpSchema)
			  val gqo = buildGProMOp(GProM_JNA.GProMNodeTag.GProM_T_SetOperator, gpChildren, toQoScm, Seq(), Seq(), null)
			  val gpunionop = new GProMSetOperator.ByValue(gqo, GProM_JNA.GProMSetOpType.GProM_SETOP_UNION)
			  gpChildren.map(setGProMQueryOperatorParentsList(_, gpunionop))
			  gpunionop
			}
			case Limit(offset, limit, query) => {
			  val cond = ExpressionUtils.makeAnd(Comparison(Cmp.Gt, IntPrimitive(100000000L), IntPrimitive(offset)), Comparison(Cmp.Lt, IntPrimitive(-100000000L), IntPrimitive(offset + limit.getOrElse(-1L))))
			  val toQoScm = translateMimirSchemaToGProMSchema("SELECT", mimirOpSchema)
        val gqo = buildGProMOp(GProM_JNA.GProMNodeTag.GProM_T_SelectionOperator, gpChildren, toQoScm, Seq(),Seq(), null) 
        val gpselop = new GProMSelectionOperator.ByValue(gqo, new GProMNode.ByReference(translateMimirExpressionToGProMStructure(mimirOperator.children, cond).getPointer) )
			  gpChildren.map(setGProMQueryOperatorParentsList(_, gpselop))
			  gpselop
			}
			case Table(name, alias, sch, inmeta) => {
			  val realSchema = db.backend.getTableSchema(name) match {
			    case Some(rsch) => rsch
			    case None => throw new Exception(s"Cant get schema for table operator: table: $name alias: $alias")
			  }
			  val requireProjection = 
			  sch.zip(realSchema).flatMap {
			    case (tableSchEl, realSchEl) => {
			      if(tableSchEl._1.equals(realSchEl._1))
			        None
			      else
			        Some((tableSchEl._1,realSchEl._1))
			    }
			  }.toMap
			  
			  //TODO: we can do this better
			  //if there were no table op schema rewrites then just return the table
			  // otherwise add a projection that renames  
			  val tableOp = {
			    val toQoScm = translateMimirSchemaToGProMSchema(alias, mimirOpSchema)
  			  //val gqoProps = createDefaultGProMTablePropertiesMap(alias, Seq(Provenance.rowidColnameBase))
  			  val gqo = buildGProMOp(GProM_JNA.GProMNodeTag.GProM_T_TableAccessOperator,gpChildren,toQoScm,Seq(),Seq(),null)//new GProMNode.ByReference(gqoProps.getPointer))
    			new GProMTableAccessOperator.ByValue(gqo,name,null)
			  }
			  val meta = inmeta ++ (userProv match {
			    case None => Seq()
			    case Some(userProvMeta) => Seq(userProvMeta)
			  })
			  if(requireProjection.isEmpty) 
		    {
			    val projSch = meta match {
			      case Seq() => sch
			      case _ => sch.filterNot(el => meta.map(_._1).contains(el._1 ))
			    }
			    val (properties, provMeta) = meta.find(_._1.equals(Provenance.rowidColnameBase)) match {
		        case None => (null, Seq())
		        case Some(pm) => (new GProMNode.ByReference(createDefaultGProMTablePropertiesMap(alias, Seq(pm._1)).getPointer),
		            Seq((pm._1, Function("monotonically_increasing_id",Seq()))))
		      }
		      val gpproj = Project(projSch.map(col => {
            ProjectArg(col._1, Var(col._1))
          }) ++ meta.filterNot(_._1.equals(Provenance.rowidColnameBase)).map(metaEl => ProjectArg(metaEl._1, metaEl._2 )) 
          ++ provMeta.map(metaEl => ProjectArg(metaEl._1, metaEl._2 )), 
          Table(name, alias, sch, Seq()))
          
          
          try{
          val outSchema = db.typechecker.schemaOf(gpproj)
          val toQoScm = translateMimirSchemaToGProMSchema("PROJECTION", outSchema)
    			val gqo = buildGProMOp(GProM_JNA.GProMNodeTag.GProM_T_ProjectionOperator,Seq(tableOp),toQoScm,Seq(),/*provMeta.map(pm => outSchema.map(_._1).indexOf(pm._1))*/Seq(),properties)
    			val gProjOp = new GProMProjectionOperator.ByValue(gqo, scalaListToGProMList(gpproj.columns.map(col => translateMimirExpressionToGProMStructure(gpproj.children, col.expression))))
    			gpChildren.map(setGProMQueryOperatorParentsList(_, gProjOp))
    			gProjOp
          }catch {
            case t: Throwable => throw new Exception(s"WTF-------------pr------------------->\n$gpproj\n$projSch\nprovMeta", t)
          }
			  }
			  else{
  			  val projSch = meta match {
			      case Seq() => mimirOperator.columnNames
			      case _ => mimirOperator.columnNames.filterNot(el => meta.map(_._1).contains(el))
			    }
  			  val (properties, provMeta) = meta.find(_._1.equals(Provenance.rowidColnameBase)) match {
		        case None => (null, Seq())
		        case Some(pm) => (new GProMNode.ByReference(createDefaultGProMTablePropertiesMap(alias, Seq(pm._1)).getPointer),
		            Seq((pm._1, Function("monotonically_increasing_id",Seq()))))
		      }
		      val gpproj = Project(
            projSch.map { col =>
              requireProjection.get(col) match {  
                case Some(target) => ProjectArg( col, Var(target)) 
                case None => ProjectArg( col, Var(col)) 
                }
            } ++ meta.filterNot(_._1.equals(Provenance.rowidColnameBase)).map(metaEl => ProjectArg(metaEl._1, metaEl._2 )) 
            ++ provMeta.map(metaEl => ProjectArg(metaEl._1, metaEl._2 )),
            Table(name, alias, sch, Seq())
          )
			   try{
          val outSchema = db.typechecker.schemaOf(gpproj)
          val toQoScm = translateMimirSchemaToGProMSchema("PROJECTION", outSchema)
    			val gqo = buildGProMOp(GProM_JNA.GProMNodeTag.GProM_T_ProjectionOperator,Seq(tableOp),toQoScm,Seq(),/*provMeta.map(pm => outSchema.map(_._1).indexOf(pm._1))*/Seq(),properties)
    			val gProjOp = new GProMProjectionOperator.ByValue(gqo, scalaListToGProMList(gpproj.columns.map(col => translateMimirExpressionToGProMStructure(gpproj.children, col.expression))))
    			gpChildren.map(setGProMQueryOperatorParentsList(_, gProjOp))
    			gProjOp
          }catch {
            case t: Throwable => throw new Exception(s"WTF-------------npr------------------->\n$gpproj\n$projSch\nprovMeta", t)
          }
			  }
          
			  /*val tableSchema = mimirOpSchema.filterNot(sche => sche._1.equals("ROWID") || sche._1.equals(Provenance.rowidColnameBase)) :+(Provenance.rowidColnameBase, TRowId())//:+(RowIdVar().toString(), TRowId())
			  val toQoScm = translateMimirSchemaToGProMSchema(alias, tableSchema)
			  val gqoProps = createDefaultGProMTablePropertiesMap(alias, Seq(Provenance.rowidColnameBase))
			  val gqo = buildGProMOp(GProM_JNA.GProMNodeTag.GProM_T_TableAccessOperator,gpChildren,toQoScm,Seq(),Seq(),new GProMNode.ByReference(gqoProps.getPointer))
  			new GProMTableAccessOperator.ByValue(gqo,name,null)*/
			}
			case View(_, query, _) => {
			 gpChildren.head
			}
      case AdaptiveView(_, _, query, _) => {
       gpChildren.head
      }
      case HardTable(schema, data) => {
        val htSchema = mimirOpSchema.filterNot(sche => sche._1.equals("ROWID") || sche._1.equals(Provenance.rowidColnameBase)) :+(Provenance.rowidColnameBase, TRowId())
        val htData = if(mimirOpSchema.map(_._1).contains(Provenance.rowidColnameBase)) {
          data.zipWithIndex.map(row => scalaListToGProMList(row._1.map(cell => translateMimirExpressionToGProMStructure(mimirOperator.children,cell))))
        } else data.zipWithIndex.map(row => scalaListToGProMList(row._1.map(cell => translateMimirExpressionToGProMStructure(mimirOperator.children,cell)):+translateMimirExpressionToGProMStructure(mimirOperator.children,RowIdPrimitive((row._2).toString()))))
        val toQoScm = translateMimirSchemaToGProMSchema("HARD_TABLE", htSchema)
			  //val gqoProps = createDefaultGProMTablePropertiesMap("HARD_TABLE", Seq(Provenance.rowidColnameBase))
        val gqo = buildGProMOp(GProM_JNA.GProMNodeTag.GProM_T_ConstRelOperator, gpChildren, toQoScm, Seq(), Seq(), null)//new GProMNode.ByReference(gqoProps.getPointer))
			  new GProMConstRelOperator.ByValue(gqo, scalaListToGProMList(htData))
      }
			case Sort(sortCols, src) => {
			  val toQoScm = translateMimirSchemaToGProMSchema("ORDER", mimirOpSchema)
        val gqo = buildGProMOp(GProM_JNA.GProMNodeTag.GProM_T_OrderOperator, gpChildren, toQoScm, Seq(), Seq(), null) 
        val gporderexprs = scalaListToGProMList(sortCols.map(sortCol => new GProMOrderExpr( GProM_JNA.GProMNodeTag.GProM_T_OrderExpr, new GProMNode.ByReference(translateMimirExpressionToGProMStructure(mimirOperator.children, sortCol.expression).getPointer), {if(sortCol.ascending) 0; else 1; }, 0 ) ) )
        val gporderop = new GProMOrderOperator.ByValue(gqo, gporderexprs )
			  gpChildren.map(setGProMQueryOperatorParentsList(_, gporderop))
			  gporderop
			}
		}
      }catch {
        case t: Throwable => throw new Exception(s"Error Translating operator: \n${mimirOperator}",t)
      }  }
  }
  
  val FN_UNCERT_WRAPPER = "UNCERT"
  
  def translateMimirExpressionToGProMStructure(ctxOpers:Seq[Operator], mimirExpr:Expression) : GProMStructure = {
     mimirExpr match {
      case Comparison(op, lhs, rhs) => {
        val cmpOp = op match {
          case  Cmp.Eq => "=" 
          case  Cmp.Neq  => "<>" 
          case  Cmp.Gt  => ">" 
          case  Cmp.Gte  => ">=" 
          case  Cmp.Lt  => "<" 
          case  Cmp.Lte  => "<=" 
          case  Cmp.Like  => "LIKE" 
          case  Cmp.NotLike => "NOT LIKE" 
          case x => throw new Exception("Invalid operand '"+x+"'")
        }
        val list = new GProMList.ByReference()
         list.`type` = GProM_JNA.GProMNodeTag.GProM_T_List
         list.head = createGProMListCell(translateMimirExpressionToGProMStructure(ctxOpers,lhs)) 
         list.head.next = createGProMListCell(translateMimirExpressionToGProMStructure(ctxOpers,rhs))      
         list.length = 2;
         new GProMOperator.ByValue(GProM_JNA.GProMNodeTag.GProM_T_Operator, cmpOp,list)
      }
      case Arithmetic(op, lhs, rhs) => {
        val aritOp = op match {
          case  Arith.Add => "+" 
          case  Arith.Sub => "-" 
          case  Arith.Mult => "*" 
          case  Arith.Div => "/" 
          case  Arith.BitAnd => "&" 
          case  Arith.BitOr => "|" 
          case  Arith.And => "AND" 
          case  Arith.Or => "OR" 
          case x => throw new Exception("Invalid operand '"+x+"'")
        }
        val list = new GProMList.ByReference()
         list.`type` = GProM_JNA.GProMNodeTag.GProM_T_List
         list.head = createGProMListCell(translateMimirExpressionToGProMStructure(ctxOpers,lhs)) 
         list.head.next = createGProMListCell(translateMimirExpressionToGProMStructure(ctxOpers,rhs))      
         list.length = 2;
         new GProMOperator.ByValue(GProM_JNA.GProMNodeTag.GProM_T_Operator, aritOp,list)
      }
      case Conditional(cond, thenClause, elseClause) => {
        val whenThen = new GProMCaseWhen.ByValue(GProM_JNA.GProMNodeTag.GProM_T_CaseWhen, new GProMNode.ByReference(translateMimirExpressionToGProMStructure(ctxOpers,cond).getPointer), new GProMNode.ByReference(translateMimirExpressionToGProMStructure(ctxOpers, thenClause ).getPointer))
        val list = new GProMList.ByReference()
         list.`type` = GProM_JNA.GProMNodeTag.GProM_T_List
         list.head = createGProMListCell(whenThen) 
         list.length = 1;
         new GProMCaseExpr.ByValue(GProM_JNA.GProMNodeTag.GProM_T_CaseExpr,null, list, new GProMNode.ByReference(translateMimirExpressionToGProMStructure(ctxOpers, elseClause).getPointer))
      }
      case primitive : PrimitiveValue => translateMimirPrimitiveExpressionToGProMConstant(primitive)
      case Var(v) => {
       val schemas = ctxOpers.map(ctxOper => db.typechecker.schemaOf(ctxOper))
       val (schIdx, schElIdxNonAgg) = schemas.zipWithIndex.map(schema => (schema._2, schema._1.indexWhere(_._1.equals(v)))) match {
         case Seq() => throw new Exception(s"Error Translating: column $v not found in context: [\n${schemas.map(_.mkString(",")).mkString("\n")}\n]")
         case Seq(idxs) => idxs
         case x => x.head
       }
       val schElIdx = ctxOpers(schIdx) match {
        case View(_, Aggregate(gb,_,_), _) if !gb.isEmpty => if(schElIdxNonAgg == 0) schemas(schIdx).length-1 else schElIdxNonAgg - 1
        case AdaptiveView(_, _, Aggregate(gb,_,_), _) if !gb.isEmpty => if(schElIdxNonAgg == 0) schemas(schIdx).length-1 else schElIdxNonAgg - 1
        case Aggregate(gb,_,_) if !gb.isEmpty => if(schElIdxNonAgg == 0) schemas(schIdx).length-1 else schElIdxNonAgg - 1
        case _ => schElIdxNonAgg
       }
        val schemasEl = try {
          schemas(schIdx)(schElIdx)
        }catch {
          case t: Throwable => {
            if(v.startsWith(Provenance.rowidColnameBase))
              (v, TRowId())
            else
              throw new Exception(s"gprom OperatorTranslator error: missing Variable: $v \n${ctxOpers.mkString("\n\n")}")
          }
        }
        val attrRef = new GProMAttributeReference.ByValue(GProM_JNA.GProMNodeTag.GProM_T_AttributeReference, v, 0, schElIdx, 0, getGProMDataTypeFromMimirType(schemasEl._2 ))
        if(!attrRef.name.equals(v)){
          logger.debug("----------------------------------------------------------------------------------------------------------------")
          logger.debug(s"--------------------------Attribute Ref Changed (Mimir->GP): $v => ${attrRef.name}-----------------------------")
          logger.debug("----------------------------------------------------------------------------------------------------------------")
        }
        attrRef
      }
      case RowIdVar() => {
        logger.debug(s"-----------> AttributeRef Conversion (Mimir->GP): RowIdVar(ROWID) => ROWNUMBEREXPE")
        val ridexpr = new GProMRowNumExpr.ByValue(GProM_JNA.GProMNodeTag.GProM_T_RowNumExpr)
        ridexpr
      }
      case Function(op, params) => {
        val gpromExprList = scalaListToGProMList(params.map(translateMimirExpressionToGProMStructure(ctxOpers, _)))
        val gpromFunc = new GProMFunctionCall.ByValue(GProM_JNA.GProMNodeTag.GProM_T_FunctionCall, op, gpromExprList, 0)
        gpromFunc
      }
      case VGTerm(name, idx, args, hints) => {
        val gpromExprList = scalaListToGProMList(Seq(translateMimirExpressionToGProMStructure(ctxOpers,Function(CTables.FN_TEMP_ENCODED, Seq(StringPrimitive(name), IntPrimitive(idx)).union(args.union(hints))))))
        val gpromFunc = new GProMFunctionCall.ByValue(GProM_JNA.GProMNodeTag.GProM_T_FunctionCall, FN_UNCERT_WRAPPER, gpromExprList, 0)
        gpromFunc
      }
      case IsNullExpression(expr) => {
        val gpromExpr = translateMimirExpressionToGProMStructure(ctxOpers,expr)
        val gpromIsNullExpr = new GProMIsNullExpr.ByValue(GProM_JNA.GProMNodeTag.GProM_T_IsNullExpr, new GProMNode.ByReference(gpromExpr.getPointer))
        gpromIsNullExpr
      }
      case Not(expr) => {
        val gpromExprList = scalaListToGProMList(Seq(translateMimirExpressionToGProMStructure(ctxOpers, expr)))
        val gpromFunc = new GProMFunctionCall.ByValue(GProM_JNA.GProMNodeTag.GProM_T_FunctionCall, "NOT", gpromExprList, 0)
        gpromFunc
      }
      case x => {
        throw new Exception("Expression Translation not implemented '"+x+"'")
      }
    }
  }
  
  def translateMimirPrimitiveExpressionToGProMConstant(mimirPrimitive : PrimitiveValue) : GProMConstant.ByValue = {
    val typeValueIsNull = mimirPrimitive match {
      case IntPrimitive(v) => {
        val intPtr = new Memory(Native.getNativeSize(classOf[Int]))
        intPtr.setInt(0, v.asInstanceOf[Int]);
        (GProM_JNA.GProMDataType.GProM_DT_INT,intPtr,0)
      }
      case StringPrimitive(v) => {
        val strPtr = new Memory(v.length()+1)
        strPtr.setString(0, v);
        (GProM_JNA.GProMDataType.GProM_DT_STRING,strPtr,0)
      }
      case FloatPrimitive(v) => {
        val fltPtr = new Memory(Native.getNativeSize(classOf[Float]))
        fltPtr.setFloat(0, v.asInstanceOf[Float]);
        (GProM_JNA.GProMDataType.GProM_DT_FLOAT,fltPtr,0)
      }
      case RowIdPrimitive(v) => {
        val strPtr = new Memory(v.length()+1)
        strPtr.setString(0, v);
        (GProM_JNA.GProMDataType.GProM_DT_STRING,strPtr,0)
        //throw new Exception("Primitive Expression Translation not implemented '"+v+"'")
      }
      case BoolPrimitive(v) => {
        val intPtr = new Memory(Native.getNativeSize(classOf[Int]))
        val boolToInt = if(v) 1; else 0;
        intPtr.setInt(0, boolToInt);
        (GProM_JNA.GProMDataType.GProM_DT_BOOL,intPtr,0)
      }
      case NullPrimitive() => {
        val intPtr = new Memory(Native.getNativeSize(classOf[Int]))
        intPtr.setInt(0,0);
        (GProM_JNA.GProMDataType.GProM_DT_INT,intPtr,1)
      }
      case d:DatePrimitive => {
        val dtStr = d.asString
        val strPtr = new Memory(dtStr.length()+1)
        strPtr.setString(0, dtStr);
        (GProM_JNA.GProMDataType.GProM_DT_STRING,strPtr,0)
      }
      case t:TimestampPrimitive => {
        val dtStr = t.asString
        val strPtr = new Memory(dtStr.length()+1)
        strPtr.setString(0, dtStr);
        (GProM_JNA.GProMDataType.GProM_DT_STRING,strPtr,0)
      }
      case i:IntervalPrimitive => {
        val dtStr = i.asString
        val strPtr = new Memory(dtStr.length()+1)
        strPtr.setString(0, dtStr);
        (GProM_JNA.GProMDataType.GProM_DT_STRING,strPtr,0)
      }
      case TypePrimitive(t) => {
        val v = Type.id(t)
        val intPtr = new Memory(Native.getNativeSize(classOf[Int]))
        intPtr.setInt(0, v.asInstanceOf[Int]);
        (GProM_JNA.GProMDataType.GProM_DT_INT,intPtr,0)
      }
    }
    val gpc = new GProMConstant.ByValue(GProM_JNA.GProMNodeTag.GProM_T_Constant, typeValueIsNull._1, typeValueIsNull._2, typeValueIsNull._3)
    gpc
  }
  
  def createDefaultGProMTablePropertiesMap(tableName:String, provCols:Seq[String] = Seq("ROWID")) : GProMHashMap = {
    val hasProvMapElemKey  = translateMimirPrimitiveExpressionToGProMConstant(StringPrimitive("HAS_PROVENANCE") ) 
    val hasProvMapElemValue = translateMimirPrimitiveExpressionToGProMConstant(BoolPrimitive(true) )  
    val provRelMapElemKey  = translateMimirPrimitiveExpressionToGProMConstant(StringPrimitive("PROVENANCE_REL_NAME") ) 
    val provRelMapElemValue = translateMimirPrimitiveExpressionToGProMConstant(StringPrimitive(tableName) ) 
    val provAttrMapElemKey  = translateMimirPrimitiveExpressionToGProMConstant(StringPrimitive("USER_PROV_ATTRS") ) 
    val provAttrMapElemValue = scalaListToGProMList( provCols.map(provCol => translateMimirPrimitiveExpressionToGProMConstant(StringPrimitive(provCol)))) 
    provAttrMapElemValue.write()
    var gphashmap = GProMWrapper.inst.gpromAddToMap(null, hasProvMapElemKey.getPointer, hasProvMapElemValue.getPointer)
    gphashmap = GProMWrapper.inst.gpromAddToMap(gphashmap.getPointer, provRelMapElemKey.getPointer, provRelMapElemValue.getPointer)
    gphashmap = GProMWrapper.inst.gpromAddToMap(gphashmap.getPointer, provAttrMapElemKey.getPointer, provAttrMapElemValue.getPointer)
    gphashmap
  }
  
  def createDefaultGProMAggrPropertiesMap(relName:String, groupByCols:Seq[String]) : GProMHashMap = {
    val hasProvMapElemKey  = translateMimirPrimitiveExpressionToGProMConstant(StringPrimitive("HAS_PROVENANCE") ) 
    val hasProvMapElemValue = translateMimirPrimitiveExpressionToGProMConstant(BoolPrimitive(true) )  
    val provRelMapElemKey  = translateMimirPrimitiveExpressionToGProMConstant(StringPrimitive("PROVENANCE_REL_NAME") ) 
    val provRelMapElemValue = translateMimirPrimitiveExpressionToGProMConstant(StringPrimitive(relName) ) 
    val provAttrMapElemKey  = translateMimirPrimitiveExpressionToGProMConstant(StringPrimitive("USER_PROV_ATTRS") ) 
    val provAttrMapElemValue = scalaListToGProMList( groupByCols.map(gbCol => translateMimirPrimitiveExpressionToGProMConstant(StringPrimitive(gbCol)))) 
    provAttrMapElemValue.write()
    var gphashmap = GProMWrapper.inst.gpromAddToMap(null, hasProvMapElemKey.getPointer, hasProvMapElemValue.getPointer)
    gphashmap = GProMWrapper.inst.gpromAddToMap(gphashmap.getPointer, provRelMapElemKey.getPointer, provRelMapElemValue.getPointer)
    gphashmap = GProMWrapper.inst.gpromAddToMap(gphashmap.getPointer, provAttrMapElemKey.getPointer, provAttrMapElemValue.getPointer)
    gphashmap
  }
  
  def operatorOptimizations: Seq[OperatorOptimization] =
    Seq(
      ProjectRedundantColumns,
      InlineProjections,
      PushdownSelections,
      new PropagateEmptyViews(db.typechecker, db.aggregates),
      PropagateConditions,
      new OptimizeExpressions(db.compiler.optimize(_:Expression)),
      PartitionUncertainJoins,
      new PullUpUnions(db.typechecker)
    )
  
   def optimizeWithGProM(oper:Operator) : Operator = {
    org.gprom.jdbc.jna.GProM_JNA.GC_LOCK.synchronized{
      //db.backend.asInstanceOf[mimir.sql.GProMBackend].metadataLookupPlugin.setOper(oper)
        val memctx = GProMWrapper.inst.gpromCreateMemContext()
        //val memctxq = GProMWrapper.inst.createMemContextName("QUERY_CONTEXT")
        val gpromNode = scalaListToGProMList(Seq(mimirOperatorToGProMOperator(oper)))
        gpromNode.write()
        val gpromNodeStr = GProMWrapper.inst.gpromNodeToString(gpromNode.getPointer())
        logger.debug("------------------------------------------------")
        logger.debug(gpromNodeStr)
        logger.debug("------------------------------------------------")
        val optimizedGpromNode = GProMWrapper.inst.optimizeOperatorModel(gpromNode.getPointer)
        val optNodeStr = GProMWrapper.inst.gpromNodeToString(optimizedGpromNode.getPointer())
        logger.debug("------------------------------------------------")
        logger.debug(oper.toString())
        logger.debug("------------------------------------------------")
        logger.debug(optNodeStr)
        logger.debug("------------------------------------------------")
        //Thread.sleep(500)
        val opOut = gpromStructureToMimirOperator(0, optimizedGpromNode, null)
        GProMWrapper.inst.gpromFreeMemContext(memctx)
        opOut
    }
  }
   
   def compileProvenanceWithGProM(oper:Operator) : (Operator, Seq[String])  = {
    org.gprom.jdbc.jna.GProM_JNA.GC_LOCK.synchronized{
      //db.backend.asInstanceOf[mimir.sql.GProMBackend].metadataLookupPlugin.setOper(oper)
        val opOp = /*Optimizer.optimize(oper, operatorOptimizations)*/db.compiler.optimize(oper)
        val memctx = GProMWrapper.inst.gpromCreateMemContext()
        //val memctxq = GProMWrapper.inst.createMemContextName("QUERY_CONTEXT")
        val gpromNode = scalaListToGProMList(Seq(mimirOperatorToGProMOperator(ProvenanceOf(opOp),
            Some((Provenance.rowidColnameBase,Function("monotonically_increasing_id",Seq()),TRowId())))))
        gpromNode.write()
        val gpNodeStr = GProMWrapper.inst.gpromNodeToString(gpromNode.getPointer())
        logger.debug("---------------gprom pre-prov-------------------")
        logger.debug(gpNodeStr)
        logger.debug("------------------------------------------------")
        val provGpromNode = GProMWrapper.inst.provRewriteOperator(gpromNode.getPointer)
        //val optimizedGpromNode = GProMWrapper.inst.optimizeOperatorModel(provGpromNode.getPointer)
        val provNodeStr = GProMWrapper.inst.gpromNodeToString(provGpromNode.getPointer())
        logger.debug("--------------mimir pre-prov--------------------")
        logger.debug(opOp.toString())
        logger.debug("----------------gprom prov----------------------")
        logger.debug(provNodeStr)
        logger.debug("------------------------------------------------")
        
        var opOut = gpromStructureToMimirOperator(0, provGpromNode, null)
        logger.debug("--------------mimir pre recover prov-----------------")
        logger.debug(opOut.toString())
        logger.debug("-----------------------------------------------------")
        
        //GProMWrapper.inst.gpromFreeMemContext(memctxq)
        GProMWrapper.inst.gpromFreeMemContext(memctx)
        val (opRet, provCols) = provenanceFromRecover(opOut)
        opOut = /*Optimizer.optimize(opRet, operatorOptimizations)*/db.compiler.optimize(opRet)
        logger.debug("--------------mimir post recover prov----------------")
        logger.debug(opRet.toString())
        logger.debug("-----------------------------------------------------")
        //logger.debug(mimir.serialization.Json.ofOperator(opRet).toString)
        //release lock for JNA objs to gc
        (opOut, provCols)
        //(opOut, Seq())
    }
  }
   
  def compileTaintWithGProM(oper:Operator) : (Operator, Map[String,Expression], Expression)  = {
    //Thread.sleep(60000)
    org.gprom.jdbc.jna.GProM_JNA.GC_LOCK.synchronized{
        //db.backend.asInstanceOf[mimir.sql.GProMBackend].metadataLookupPlugin.setOper(oper)
        val memctx = GProMWrapper.inst.gpromCreateMemContext()
        //val memctxq = GProMWrapper.inst.createMemContextName("QUERY_CONTEXT")
        //val opOp = /*Optimizer.optimize(oper, operatorOptimizations)*/db.compiler.optimize(oper)
        val gpromNode = scalaListToGProMList(Seq(mimirOperatorToGProMOperator(oper)))
        gpromNode.write()
        val gpNodeStr = GProMWrapper.inst.gpromNodeToString(gpromNode.getPointer())
        logger.debug("---------------gprom pre-taint------------------")
        logger.debug(gpNodeStr)
        logger.debug("------------------------------------------------")
        //val optimizedGpromNode = GProMWrapper.inst.optimizeOperatorModel(gpromNode.getPointer)
        val taintGpromNode = GProMWrapper.inst.taintRewriteOperator(gpromNode.head.data.ptr_value)
        //val optimizedGpromNode = GProMWrapper.inst.optimizeOperatorModel(provGpromNode.getPointer)
        val taintNodeStr = GProMWrapper.inst.gpromNodeToString(taintGpromNode.getPointer())
        logger.debug("---------------mimir pre-taint------------------")
        logger.debug(oper.toString())
        logger.debug("-----------------gprom taint--------------------")
        logger.debug(taintNodeStr)
        logger.debug("------------------------------------------------")
        var opOut = gpromStructureToMimirOperator(0, taintGpromNode, null)
        logger.debug("------------mimir pre recover taint-------------")
        logger.debug(opOut.toString())
        logger.debug("------------------------------------------------")
        
        //GProMWrapper.inst.gpromFreeMemContext(memctxq)
        GProMWrapper.inst.gpromFreeMemContext(memctx)
        val (opRet, colTaint, rowTaint) = taintFromRecover(opOut)
        //opOut = db.compiler.optimize(opRet)
        logger.debug("--------mimir post recover taint pre-opt--------")
        logger.debug(opRet.toString())
        opOut = /*Optimizer.optimize(opRet, operatorOptimizations)*/db.compiler.optimize(opRet)
        opOut = fixJoins(opOut)
        logger.debug("------------mimir post recover taint------------")
        logger.debug(opOut.toString())
        logger.debug("--------------------taint-----------------------")
        logger.debug(colTaint.toString())
        logger.debug("------------------------------------------------")
        //logger.debug(mimir.serialization.Json.ofOperator(opRet).toString)
        //release lock for JNA objs to gc
        (opOut,  colTaint, rowTaint)
        //(opOut, Map(), IntPrimitive(1))
    }
  }
  
  //doesn't work 
  /** combine gprom provenance and taint compilation into one method.
  * combining these steps saves a back and forth translation 
  * of the operator tree to/from gprom
  * 
  *  @param oper the operator to compile provenance and taint for
  *  @returns (the rewritten operator, provenance columns, column taint, row taint)
  */ 
  def compileProvenanceAndTaintWithGProM(oper:Operator) : (Operator, Seq[String], Map[String,Expression], Expression)  = {
    //Thread.sleep(60000)
    org.gprom.jdbc.jna.GProM_JNA.GC_LOCK.synchronized{
        //db.backend.asInstanceOf[mimir.sql.GProMBackend].metadataLookupPlugin.setOper(oper)
        val memctx = GProMWrapper.inst.gpromCreateMemContext()
        //val memctxq = GProMWrapper.inst.createMemContextName("QUERY_CONTEXT")
        val gpromNode = mimirOperatorToGProMList(ProvenanceOf(oper))
        gpromNode.write()
        val gpNodeStr = GProMWrapper.inst.gpromNodeToString(gpromNode.getPointer())
        logger.debug("------------------------------------------------")
        logger.debug(gpNodeStr)
        logger.debug("------------------------------------------------")
       
        //rewrite for provenance with gprom 
        val provGpromNode = GProMWrapper.inst.provRewriteOperator(gpromNode.getPointer)
        //val optimizedGpromNode = GProMWrapper.inst.optimizeOperatorModel(provGpromNode.getPointer)
        val provNodeStr = GProMWrapper.inst.gpromNodeToString(provGpromNode.getPointer())
        logger.debug("------------------mimir-------------------------")
        logger.debug(oper.toString())
        logger.debug("---------------gprom prov-----------------------")
        logger.debug(provNodeStr)
        logger.debug("------------------------------------------------")
        
        //rewrite for taint
        val taintGpromNode = GProMWrapper.inst.taintRewriteOperator(provGpromNode.asInstanceOf[GProMList].head.data.ptr_value)
        //val optimizedGpromNode = GProMWrapper.inst.optimizeOperatorModel(provGpromNode.getPointer)
        val taintNodeStr = GProMWrapper.inst.gpromNodeToString(provGpromNode.getPointer())
        logger.debug("-----------------gprom taint--------------------")
        logger.debug(taintNodeStr)
        logger.debug("------------------------------------------------")
        var opOut = gpromStructureToMimirOperator(0, taintGpromNode, null)
        logger.debug("--------------mimir pre recover-----------------")
        logger.debug(opOut.toString())
        logger.debug("------------------------------------------------")
        
        //GProMWrapper.inst.gpromFreeMemContext(memctxq)
        GProMWrapper.inst.gpromFreeMemContext(memctx)
        val (opRet, provCols, colTaint, rowTaint) = provAndTaintFromRecover(opOut)
        logger.debug("-----------mimir post recover prov taint--------------")
        logger.debug(opRet.toString())
        logger.debug("--------------------taint-----------------------------")
        logger.debug(colTaint.toString())
        logger.debug("------------------------------------------------------")
        //logger.debug(mimir.serialization.Json.ofOperator(opRet).toString)
        //release lock for JNA objs to gc
        (opRet, provCols, colTaint, rowTaint)
    }
  }
  
  def fixProvAgg(oper:Operator):Operator = {
    oper match {
      case agg@Aggregate(gb,_,_) if !gb.isEmpty => agg.rename(gb.map(gbe => {
        (gbe.name, "PROV_AGG_" + gbe.name.replaceAll("_", "__"))
      }):_*).addColumn(gb.map(gbe => {
        (gbe.name, Var("PROV_AGG_" + gbe.name.replaceAll("_", "__")))
      }):_*)
      case x => x.recur(fixProvAgg(_))
    }
  }
  
  def fixJoins(oper:Operator):Operator = {
    oper match {
      case loj@LeftOuterJoin(lhs, rhs, cond) => {
        if(lhs.columnNames.contains("MIMIR_COL_DET_R") && rhs.columnNames.contains("MIMIR_COL_DET_R"))
          LeftOuterJoin(lhs.rename(("MIMIR_COL_DET_R","MIMIR_COL_DET_R2")), rhs, cond)
        else loj
      }
      case join@Join(lhs, rhs) => {
        if(lhs.columnNames.contains("MIMIR_COL_DET_R") && rhs.columnNames.contains("MIMIR_COL_DET_R"))
          Join(lhs.rename(("MIMIR_COL_DET_R","MIMIR_COL_DET_R2")), rhs)
        else join
      }
      case _ => oper.recur(fixJoins)
    }
  }
  
  def applyRecover(oper:Operator): Operator = {
    oper match {
      case Recover(Project(projArgs, subj), invisScm) => {
        Project( subj.columnNames.map(col => ProjectArg(col, Var(col))) ++ invisScm.map(invisEl => ProjectArg(invisEl._2.name, Var(invisEl._2.name))), applyRecover(subj) )
      }
      case proj@Project(projArgs, Annotate(subj, invisScm)) => {
        Project( projArgs ++ invisScm.map(invisEl => ProjectArg(invisEl._2.name, Var(invisEl._2.name))), applyRecover(subj) )
        //applyRecover(subj).removeColumns(subj.columnNames.filter(col => proj.columns.contains(col) || invisScm.map(_._1).contains(col) ):_* )
      }
      case x => x.recur(applyRecover) 
    }
  }
  
  def provenanceFromRecover(oper:Operator) : (Operator, Seq[String]) = {
    oper match {
     case Recover(subj,invisScm) => {
       val provInvisScm = invisScm.filter(_._2.annotationType == ViewAnnotation.PROVENANCE)
       (fixProvAgg(applyRecover(Recover(subj, provInvisScm))), provInvisScm.map(ise => ise._2.name))
     }
     case x => throw new Exception("Recover Op required, not: "+x.toString())
    }
  }
 
  def taintFromRecover(oper:Operator) : (Operator, Map[String,Expression], Expression) = {
    oper match {
     case Recover(subj,invisScm) => {
       val taintInvisScm = invisScm.filter(_._2.annotationType == ViewAnnotation.TAINT)
       (applyRecover(Recover(subj, taintInvisScm)), taintInvisScm.filterNot(_._2.name.equals("MIMIR_COL_DET_R")).map(ise => (ise._1.replaceAll("MIMIR_COL_DET_", ""), Var(ise._2.name))).toMap, /*taintInvisScm.filter(_._2.name.equals("MIMIR_COL_DET_R")).head._2.expr*/Var("MIMIR_COL_DET_R"))
     }
     case x => throw new Exception("Recover Op required, not: "+x.toString())
    }
  }
  
  def provAndTaintFromRecover(oper:Operator) : (Operator, Seq[String], Map[String,Expression], Expression) = {
    oper match {
     case Recover(subj,invisScm) => {
       val provInvisScm = invisScm.filter(_._2.annotationType == ViewAnnotation.PROVENANCE)
       val taintInvisScm = invisScm.filter(_._2.annotationType == ViewAnnotation.TAINT)
       val provAndTaintScm = invisScm.filter(invisEl => invisEl._2.annotationType == ViewAnnotation.TAINT || invisEl._2.annotationType == ViewAnnotation.PROVENANCE)
       (applyRecover(Recover(subj, provAndTaintScm)), provInvisScm.map(ise => ise._2.name), taintInvisScm.filter(!_._2.name.equals("MIMIR_COL_DET_R")).map(ise => (ise._1.replaceAll("MIMIR_COL_DET_", ""), Var(ise._2.name))).toMap, /*taintInvisScm.filter(_._2.name.equals("MIMIR_COL_DET_R")).head._2.expr*/Var("MIMIR_COL_DET_R"))
     }
     case x => throw new Exception("Recover Op required, not: "+x.toString())
    }
  }
  
   
}