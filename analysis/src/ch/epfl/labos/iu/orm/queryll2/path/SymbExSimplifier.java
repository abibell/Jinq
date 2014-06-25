package ch.epfl.labos.iu.orm.queryll2.path;

import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.Type;

import ch.epfl.labos.iu.orm.queryll2.symbolic.ConstantValue;
import ch.epfl.labos.iu.orm.queryll2.symbolic.MethodCallValue;
import ch.epfl.labos.iu.orm.queryll2.symbolic.MethodSignature;
import ch.epfl.labos.iu.orm.queryll2.symbolic.TypedValue;
import ch.epfl.labos.iu.orm.queryll2.symbolic.TypedValue.ComparisonValue.ComparisonOp;
import ch.epfl.labos.iu.orm.queryll2.symbolic.TypedValueVisitor;

public class SymbExSimplifier<I> extends TypedValueVisitor<I, TypedValue, RuntimeException>
{
   public TypedValue defaultValue(TypedValue val, I in) 
   {
      return val;
   }
   
   /**
    * Helper for handling comparisons to zero. Returns val if nothing is
    * rewritten.
    */
   private TypedValue comparisonOpValueWithZero(TypedValue.ComparisonValue val, 
         TypedValue.ComparisonValue.ComparisonOp op, TypedValue other)
   {
      // Check for a comparison that is treated as an integer and used in a further comparison
      // (happens with methods that return true/false like String.equals())
      if ((op == TypedValue.ComparisonValue.ComparisonOp.eq 
               || op == TypedValue.ComparisonValue.ComparisonOp.ne)
            && other instanceof TypedValue.ComparisonValue)
      {
         if (op != TypedValue.ComparisonValue.ComparisonOp.eq)
            return other;
         else
            return ((TypedValue.ComparisonValue)other).inverseValue();
      }

      // Need to handle things like Util.SQLStringLike() differently,
      // so we check for things that return booleans and which are treated
      // like integers in a comparison
      if ((op == TypedValue.ComparisonValue.ComparisonOp.eq 
               || op == TypedValue.ComparisonValue.ComparisonOp.ne)
            && other.getType() == Type.BOOLEAN_TYPE)
      {
         if (op != TypedValue.ComparisonValue.ComparisonOp.eq)
            return other;
         else
            return new TypedValue.NotValue(other);
      }
      
      // Check for a cmp operator, and convert it to a direct comparison operator
      if (other instanceof TypedValue.MathOpValue
            && ((TypedValue.MathOpValue)other).op == TypedValue.MathOpValue.Op.cmp)
      {
         TypedValue newLeft = ((TypedValue.MathOpValue)other).left;
         TypedValue newRight = ((TypedValue.MathOpValue)other).right;
         return new TypedValue.ComparisonValue(op, newLeft, newRight);
      }
      
      return val;
   }
   
   public TypedValue comparisonOpValue(TypedValue.ComparisonValue val, I in) 
   {
      // Check for comparison of two integer constants
      if (val.left instanceof ConstantValue.IntegerConstant
            && val.right instanceof ConstantValue.IntegerConstant)
      {
         ConstantValue.IntegerConstant left = (ConstantValue.IntegerConstant)val.left;
         ConstantValue.IntegerConstant right = (ConstantValue.IntegerConstant)val.right;
         switch(val.compOp)
         {
            case eq: return new ConstantValue.IntegerConstant(left.val == right.val ? 1: 0); 
            case ne: return new ConstantValue.IntegerConstant(left.val != right.val ? 1: 0);
            default: break;
         }
      }
      
      // Handle comparison where one side is 0 because that pattern 
      // usually occurs when using the cmp operator or when using integers
      // as booleans
      if (val.left instanceof ConstantValue.IntegerConstant
            && ((ConstantValue.IntegerConstant)val.left).val == 0)
      {
         ComparisonOp newOp;
         switch(val.compOp)
         {
         case eq: newOp = ComparisonOp.eq; break;
         case ne: newOp = ComparisonOp.ne; break;
         case ge: newOp = ComparisonOp.le; break;
         case gt: newOp = ComparisonOp.lt; break;
         case le: newOp = ComparisonOp.ge; break;
         case lt: newOp = ComparisonOp.gt; break;
         default:
            throw new IllegalArgumentException("Unknown comparison operator");
         }
         TypedValue toReturn = comparisonOpValueWithZero(val, newOp, val.right);
         if (toReturn != val) return toReturn;
      }
      if (val.right instanceof ConstantValue.IntegerConstant
            && ((ConstantValue.IntegerConstant)val.right).val == 0)
      {
         TypedValue toReturn = comparisonOpValueWithZero(val, val.compOp, val.left);
         if (toReturn != val) return toReturn;
      }
      
      return super.comparisonOpValue(val, in);
   }

   static Map<MethodSignature, TypedValue.ComparisonValue.ComparisonOp> comparisonMethods;
   static {
      comparisonMethods = new HashMap<>();
      
      // TODO: This changes the semantics of things a little bit
      comparisonMethods.put(TransformationClassAnalyzer.stringEquals, ComparisonOp.eq);
      
      comparisonMethods.put(TransformationClassAnalyzer.dateEquals, ComparisonOp.eq);
      comparisonMethods.put(TransformationClassAnalyzer.dateBefore, ComparisonOp.lt);
      comparisonMethods.put(TransformationClassAnalyzer.dateAfter, ComparisonOp.gt);
      comparisonMethods.put(TransformationClassAnalyzer.calendarEquals, ComparisonOp.eq);
      comparisonMethods.put(TransformationClassAnalyzer.calendarBefore, ComparisonOp.lt);
      comparisonMethods.put(TransformationClassAnalyzer.calendarAfter, ComparisonOp.gt);
      comparisonMethods.put(TransformationClassAnalyzer.sqlDateEquals, ComparisonOp.eq);
      comparisonMethods.put(TransformationClassAnalyzer.sqlDateBefore, ComparisonOp.lt);
      comparisonMethods.put(TransformationClassAnalyzer.sqlDateAfter, ComparisonOp.gt);
      comparisonMethods.put(TransformationClassAnalyzer.sqlTimeEquals, ComparisonOp.eq);
      comparisonMethods.put(TransformationClassAnalyzer.sqlTimeBefore, ComparisonOp.lt);
      comparisonMethods.put(TransformationClassAnalyzer.sqlTimeAfter, ComparisonOp.gt);
      comparisonMethods.put(TransformationClassAnalyzer.sqlTimestampEquals, ComparisonOp.eq);
      comparisonMethods.put(TransformationClassAnalyzer.sqlTimestampBefore, ComparisonOp.lt);
      comparisonMethods.put(TransformationClassAnalyzer.sqlTimestampAfter, ComparisonOp.gt);
   }
   
   public TypedValue virtualMethodCallValue(MethodCallValue.VirtualMethodCallValue val, I in) 
   {
      // TODO: This changes the semantics of things a little bit
      if (comparisonMethods.containsKey(val.getSignature()))
      {
         return new TypedValue.ComparisonValue(comparisonMethods.get(val.getSignature()), val.base, val.args.get(0));
      }
      
      return methodCallValue(val, in);
   }

}
