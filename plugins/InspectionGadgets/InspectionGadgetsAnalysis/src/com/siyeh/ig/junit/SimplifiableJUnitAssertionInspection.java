/*
 * Copyright 2003-2016 Dave Griffith, Bas Leijdekkers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.siyeh.ig.junit;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiUtil;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.ig.PsiReplacementUtil;
import com.siyeh.ig.psiutils.*;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class SimplifiableJUnitAssertionInspection extends BaseInspection {

  @Override
  @NotNull
  public String getDisplayName() {
    return InspectionGadgetsBundle.message("simplifiable.junit.assertion.display.name");
  }

  @Override
  @NotNull
  protected String buildErrorString(Object... infos) {
    return InspectionGadgetsBundle.message("simplifiable.junit.assertion.problem.descriptor", infos[0]);
  }

  @Override
  public InspectionGadgetsFix buildFix(Object... infos) {
    return new SimplifyJUnitAssertFix();
  }

  private static class SimplifyJUnitAssertFix extends InspectionGadgetsFix {

    @Override
    @NotNull
    public String getFamilyName() {
      return InspectionGadgetsBundle.message("simplify.junit.assertion.simplify.quickfix");
    }

    @Override
    public void doFix(Project project, ProblemDescriptor descriptor) {
      final PsiElement methodNameIdentifier = descriptor.getPsiElement();
      final PsiElement parent = methodNameIdentifier.getParent();
      if (parent == null) {
        return;
      }
      final PsiMethodCallExpression callExpression = (PsiMethodCallExpression)parent.getParent();
      if (isAssertEqualsThatCouldBeAssertLiteral(callExpression)) {
        replaceAssertEqualsWithAssertLiteral(callExpression);
      }
      else {
        final boolean assertTrue = isAssertTrue(callExpression);
        final boolean assertFalse = isAssertFalse(callExpression);
        if (!assertTrue && !assertFalse) {
          return;
        }
        final AssertHint assertTrueFalseHint = AssertHint.createAssertTrueFalseHint(callExpression);
        if (assertTrueFalseHint == null) {
          return;
        }
        final PsiExpression position = assertTrueFalseHint.getPosition(callExpression.getArgumentList().getExpressions());
        if (isNullComparison(position)) {
          replaceAssertWithAssertNull(callExpression, (PsiBinaryExpression)position, assertTrueFalseHint.getMessage());
        }
        else if (isIdentityComparison(position)) {
          replaceAssertWithAssertSame(callExpression, (PsiBinaryExpression)position, assertTrueFalseHint.getMessage());
        }
        else if (assertTrue && isEqualityComparison(position)) {
          replaceAssertTrueWithAssertEquals(callExpression, position, assertTrueFalseHint.getMessage());
        }
        else if (isAssertThatCouldBeFail(position, !assertTrue)) {
          replaceAssertWithFail(callExpression, assertTrueFalseHint.getMessage());
        }
      }
    }

    private static void addStaticImportOrQualifier(String methodName, PsiMethodCallExpression originalMethodCall, StringBuilder out) {
      final PsiReferenceExpression methodExpression = originalMethodCall.getMethodExpression();
      final PsiExpression qualifier = methodExpression.getQualifierExpression();
      if (qualifier == null) {
        final PsiMethod method = originalMethodCall.resolveMethod();
        if (method == null) {
          return;
        }
        final PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
          return;
        }
        final String className = containingClass.getQualifiedName();
        if (className == null) {
          return;
        }
        if (!ImportUtils.addStaticImport(className, methodName, originalMethodCall)) {
          // add qualifier if old call was to JUnit4 method and adding static import failed
          out.append(className).append(".");
        }
      }
      else {
        // apparently not statically imported, keep old qualifier in new assert call
        out.append(qualifier.getText()).append('.');
      }
    }

    private static void replaceAssertWithFail(PsiMethodCallExpression callExpression, PsiExpression message) {
      @NonNls final StringBuilder newExpression = new StringBuilder();
      addStaticImportOrQualifier("fail", callExpression, newExpression);
      newExpression.append("fail(");
      if (message != null) {
        newExpression.append(message.getText());
      }
      newExpression.append(')');
      PsiReplacementUtil.replaceExpressionAndShorten(callExpression, newExpression.toString());
    }

    private static void replaceAssertTrueWithAssertEquals(PsiMethodCallExpression callExpression,
                                                          final PsiExpression position,
                                                          final PsiExpression message) {


      PsiExpression lhs = null;
      PsiExpression rhs = null;
      if (position instanceof PsiBinaryExpression) {
        final PsiBinaryExpression binaryExpression = (PsiBinaryExpression)position;
        lhs = binaryExpression.getLOperand();
        rhs = binaryExpression.getROperand();
      }
      else if (position instanceof PsiMethodCallExpression) {
        final PsiMethodCallExpression call = (PsiMethodCallExpression)position;
        final PsiReferenceExpression equalityMethodExpression = call.getMethodExpression();
        final PsiExpressionList equalityArgumentList = call.getArgumentList();
        final PsiExpression[] equalityArgs = equalityArgumentList.getExpressions();
        rhs = equalityArgs[0];
        lhs = equalityMethodExpression.getQualifierExpression();
      }
      if (!(lhs instanceof PsiLiteralExpression) && rhs instanceof PsiLiteralExpression) {
        final PsiExpression temp = lhs;
        lhs = rhs;
        rhs = temp;
      }
      if (lhs == null || rhs == null) {
        return;
      }
      @NonNls final StringBuilder newExpression = new StringBuilder();
      addStaticImportOrQualifier("assertEquals", callExpression, newExpression);
      newExpression.append("assertEquals(");
      if (message != null) {
        newExpression.append(message.getText()).append(',');
      }
      final PsiType lhsType = lhs.getType();
      final PsiType rhsType = rhs.getType();
      if (lhsType != null && rhsType != null && PsiUtil.isLanguageLevel5OrHigher(lhs)) {
        if (isPrimitiveAndBoxedInteger(lhsType, rhsType)) {
          final PsiPrimitiveType unboxedType = PsiPrimitiveType.getUnboxedType(rhsType);
          assert unboxedType != null;
          newExpression.append(lhs.getText()).append(",(").append(unboxedType.getCanonicalText()).append(')').append(rhs.getText());
        }
        else if (isPrimitiveAndBoxedInteger(rhsType, lhsType)) {
          final PsiPrimitiveType unboxedType = PsiPrimitiveType.getUnboxedType(lhsType);
          assert unboxedType != null;
          newExpression.append('(').append(unboxedType.getCanonicalText()).append(')').append(lhs.getText()).append(',').append(rhs.getText());
        }
        else {
          newExpression.append(lhs.getText()).append(',').append(rhs.getText());
        }
      }
      else {
        newExpression.append(lhs.getText()).append(',').append(rhs.getText());
      }
      if (TypeUtils.hasFloatingPointType(lhs) || TypeUtils.hasFloatingPointType(rhs) ||
          isPrimitiveAndBoxedFloat(lhsType, rhsType) || isPrimitiveAndBoxedFloat(rhsType, lhsType)) {
        newExpression.append(",0.0");
      }
      newExpression.append(')');
      PsiReplacementUtil.replaceExpressionAndShorten(callExpression, newExpression.toString());
    }

    private static boolean isPrimitiveAndBoxedInteger(PsiType lhsType, PsiType rhsType) {
      return lhsType instanceof PsiPrimitiveType && rhsType instanceof PsiClassType && PsiType.LONG.isAssignableFrom(rhsType);
    }

    private static boolean isPrimitiveAndBoxedFloat(PsiType lhsType, PsiType rhsType) {
      return lhsType instanceof PsiPrimitiveType && rhsType instanceof PsiClassType &&
             (PsiType.DOUBLE.equals(rhsType) && PsiType.FLOAT.equals(rhsType));
    }

    private static void replaceAssertWithAssertNull(PsiMethodCallExpression callExpression,
                                                    final PsiBinaryExpression binaryExpression,
                                                    final PsiExpression message) {
      final PsiExpression lhs = binaryExpression.getLOperand();
      PsiExpression rhs = binaryExpression.getROperand();
      if (rhs == null) {
        return;
      }
      final IElementType tokenType = binaryExpression.getOperationTokenType();
      if (!(lhs instanceof PsiLiteralExpression) && rhs instanceof PsiLiteralExpression) {
        rhs = lhs;
      }
      @NonNls final StringBuilder newExpression = new StringBuilder();
      final PsiReferenceExpression methodExpression = callExpression.getMethodExpression();
      @NonNls final String methodName = methodExpression.getReferenceName();
      @NonNls final String memberName;
      if ("assertFalse".equals(methodName) ^ tokenType.equals(JavaTokenType.NE)) {
        memberName = "assertNotNull";
      }
      else {
        memberName = "assertNull";
      }
      addStaticImportOrQualifier(memberName, callExpression, newExpression);
      newExpression.append(memberName).append('(');
      if (message != null) {
        newExpression.append(message.getText()).append(',');
      }
      newExpression.append(rhs.getText()).append(')');
      PsiReplacementUtil.replaceExpressionAndShorten(callExpression, newExpression.toString());
    }

    private static void replaceAssertWithAssertSame(PsiMethodCallExpression callExpression,
                                                    final PsiBinaryExpression position,
                                                    final PsiExpression message) {
      PsiExpression lhs = position.getLOperand();
      PsiExpression rhs = position.getROperand();
      final IElementType tokenType = position.getOperationTokenType();
      if (!(lhs instanceof PsiLiteralExpression) && rhs instanceof PsiLiteralExpression) {
        final PsiExpression temp = lhs;
        lhs = rhs;
        rhs = temp;
      }
      if (rhs == null) {
        return;
      }
      @NonNls final StringBuilder newExpression = new StringBuilder();
      final PsiReferenceExpression methodExpression = callExpression.getMethodExpression();
      @NonNls final String methodName = methodExpression.getReferenceName();
      @NonNls final String memberName;
      if ("assertFalse".equals(methodName) ^ tokenType.equals(JavaTokenType.NE)) {
        memberName = "assertNotSame";
      }
      else {
        memberName = "assertSame";
      }
      addStaticImportOrQualifier(memberName, callExpression, newExpression);
      newExpression.append(memberName).append('(');
      if (message != null) {
        newExpression.append(message.getText()).append(',');
      }
      newExpression.append(lhs.getText()).append(',').append(rhs.getText()).append(')');
      PsiReplacementUtil.replaceExpressionAndShorten(callExpression, newExpression.toString());
    }

    private static void replaceAssertEqualsWithAssertLiteral(PsiMethodCallExpression callExpression) {
      final AssertHint assertHint = AssertHint.createAssertEqualsHint(callExpression);
      if (assertHint == null) return;

      final PsiExpressionList argumentList = callExpression.getArgumentList();
      final PsiExpression[] arguments = argumentList.getExpressions();
      final int argIndex = assertHint.getArgIndex();
      final PsiExpression firstTestArgument = arguments[argIndex];
      final PsiExpression secondTestArgument = arguments[argIndex + 1];
      final String literalValue;
      final String compareValue;
      if (isSimpleLiteral(firstTestArgument, secondTestArgument)) {
        literalValue = firstTestArgument.getText();
        compareValue = secondTestArgument.getText();
      }
      else {
        literalValue = secondTestArgument.getText();
        compareValue = firstTestArgument.getText();
      }
      final String uppercaseLiteralValue = Character.toUpperCase(literalValue.charAt(0)) + literalValue.substring(1);
      @NonNls final StringBuilder newExpression = new StringBuilder();
      @NonNls final String methodName = "assert" + uppercaseLiteralValue;
      addStaticImportOrQualifier(methodName, callExpression, newExpression);
      newExpression.append(methodName).append('(');
      PsiExpression message = assertHint.getMessage();
      if (message != null) {
        newExpression.append(message.getText()).append(',');
      }
      newExpression.append(compareValue).append(')');
      PsiReplacementUtil.replaceExpressionAndShorten(callExpression, newExpression.toString());
    }
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new SimplifiableJUnitAssertionVisitor();
  }

  private static class SimplifiableJUnitAssertionVisitor extends BaseInspectionVisitor {

    @Override
    public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
      super.visitMethodCallExpression(expression);
      if (isAssertEqualsThatCouldBeAssertLiteral(expression)) {
        registerMethodCallError(expression, getReplacementMethodName(expression));
      }
      else {
        final boolean assertTrue = isAssertTrue(expression);
        final boolean assertFalse = isAssertFalse(expression);
        if (!assertTrue && !assertFalse) {
          return;
        }

        final AssertHint assertTrueFalseHint = AssertHint.createAssertTrueFalseHint(expression);
        if (assertTrueFalseHint == null) {
          return;
        }

        final PsiExpression position = assertTrueFalseHint.getPosition(expression.getArgumentList().getExpressions());
        if (isNullComparison(position)) {
          registerMethodCallError(expression, hasEqEqExpressionArgument(position) ? "assertNull()" : "assertNotNull()");
        }
        else if (isIdentityComparison(position)) {
          registerMethodCallError(expression, hasEqEqExpressionArgument(position) ? "assertSame()" : "assertNotSame()");
        }
        else if (assertTrue && isEqualityComparison(position)) {
          registerMethodCallError(expression, "assertEquals()");
        }
        else if (isAssertThatCouldBeFail(position, !assertTrue)) {
          registerMethodCallError(expression, "fail()");
        }
      }
    }

    @NonNls
    private static String getReplacementMethodName(PsiMethodCallExpression expression) {
      final PsiExpressionList argumentList = expression.getArgumentList();
      final PsiExpression[] arguments = argumentList.getExpressions();
      final PsiExpression firstArgument = arguments[0];
      final PsiExpression secondArgument = arguments[1];
      final PsiLiteralExpression literalExpression;
      if (firstArgument instanceof PsiLiteralExpression) {
        literalExpression = (PsiLiteralExpression)firstArgument;
      }
      else if (secondArgument instanceof PsiLiteralExpression) {
        literalExpression = (PsiLiteralExpression)secondArgument;
      }
      else {
        return "";
      }
      final Object value = literalExpression.getValue();
      if (value == Boolean.TRUE) {
        return "assertTrue()";
      }
      else if (value == Boolean.FALSE) {
        return "assertFalse()";
      }
      else if (value == null) {
        return "assertNull()";
      }
      return "";
    }

    private static boolean hasEqEqExpressionArgument(PsiExpression argument) {
      if (!(argument instanceof PsiBinaryExpression)) {
        return false;
      }
      final PsiBinaryExpression binaryExpression = (PsiBinaryExpression)argument;
      final IElementType tokenType = binaryExpression.getOperationTokenType();
      return JavaTokenType.EQEQ.equals(tokenType);
    }
  }

  static boolean isAssertThatCouldBeFail(PsiExpression position, boolean checkTrue) {
    return (checkTrue ? PsiKeyword.TRUE : PsiKeyword.FALSE).equals(position.getText());
  }

  static boolean isAssertEqualsThatCouldBeAssertLiteral(PsiMethodCallExpression expression) {
    final AssertHint assertHint = AssertHint.createAssertEqualsHint(expression);
    if (assertHint == null) {
      return false;
    }
    final PsiExpressionList argumentList = expression.getArgumentList();
    final PsiExpression[] arguments = argumentList.getExpressions();
    final int argIndex = assertHint.getArgIndex();
    final PsiExpression firstTestArgument = arguments[argIndex];
    final PsiExpression secondTestArgument = arguments[argIndex + 1];
    if (firstTestArgument == null || secondTestArgument == null) {
      return false;
    }
    return isSimpleLiteral(firstTestArgument, secondTestArgument) ||
           isSimpleLiteral(secondTestArgument, firstTestArgument);
  }

  static boolean isSimpleLiteral(PsiExpression expression1, PsiExpression expression2) {
    if (!(expression1 instanceof PsiLiteralExpression)) {
      return false;
    }
    final String text = expression1.getText();
    if (PsiKeyword.NULL.equals(text)) {
      return true;
    }
    if (!PsiKeyword.TRUE.equals(text) && !PsiKeyword.FALSE.equals(text)) {
      return false;
    }
    final PsiType type = expression2.getType();
    return PsiType.BOOLEAN.equals(type);
  }

  private static boolean isEqualityComparison(PsiExpression expression) {
    if (expression instanceof PsiBinaryExpression) {
      final PsiBinaryExpression binaryExpression = (PsiBinaryExpression)expression;
      final IElementType tokenType = binaryExpression.getOperationTokenType();
      if (!tokenType.equals(JavaTokenType.EQEQ)) {
        return false;
      }
      final PsiExpression lhs = binaryExpression.getLOperand();
      final PsiExpression rhs = binaryExpression.getROperand();
      if (rhs == null) {
        return false;
      }
      final PsiType type = lhs.getType();
      return type != null && ClassUtils.isPrimitive(type);
    }
    else if (expression instanceof PsiMethodCallExpression) {
      final PsiMethodCallExpression call = (PsiMethodCallExpression)expression;
      if (!MethodCallUtils.isEqualsCall(call)) {
        return false;
      }
      final PsiReferenceExpression methodExpression = call.getMethodExpression();
      return methodExpression.getQualifierExpression() != null;
    }
    return false;
  }

  private static boolean isIdentityComparison(PsiExpression expression) {
    if (!(expression instanceof PsiBinaryExpression)) {
      return false;
    }
    final PsiBinaryExpression binaryExpression = (PsiBinaryExpression)expression;
    if (!ComparisonUtils.isEqualityComparison(binaryExpression)) {
      return false;
    }
    final PsiExpression rhs = binaryExpression.getROperand();
    if (rhs == null) {
      return false;
    }
    final PsiExpression lhs = binaryExpression.getLOperand();
    final PsiType lhsType = lhs.getType();
    if (lhsType instanceof PsiPrimitiveType) {
      return false;
    }
    final PsiType rhsType = rhs.getType();
    return !(rhsType instanceof PsiPrimitiveType);
  }

  private static boolean isNullComparison(PsiExpression expression) {
    if (!(expression instanceof PsiBinaryExpression)) {
      return false;
    }
    final PsiBinaryExpression binaryExpression = (PsiBinaryExpression)expression;
    if (!ComparisonUtils.isEqualityComparison(binaryExpression)) {
      return false;
    }
    final PsiExpression rhs = binaryExpression.getROperand();
    if (rhs == null) {
      return false;
    }
    final PsiExpression lhs = binaryExpression.getLOperand();
    return PsiKeyword.NULL.equals(lhs.getText()) || PsiKeyword.NULL.equals(rhs.getText());
  }

  private static boolean isAssertTrue(@NotNull PsiMethodCallExpression expression) {
    return isAssertMethodCall(expression, "assertTrue");
  }

  private static boolean isAssertFalse(@NotNull PsiMethodCallExpression expression) {
    return isAssertMethodCall(expression, "assertFalse");
  }

  private static boolean isAssertMethodCall(@NotNull PsiMethodCallExpression expression,
    @NonNls @NotNull String assertMethodName) {
    final PsiReferenceExpression methodExpression = expression.getMethodExpression();
    @NonNls final String methodName = methodExpression.getReferenceName();
    if (!assertMethodName.equals(methodName)) {
      return false;
    }
    final PsiMethod method = (PsiMethod)methodExpression.resolve();
    if (method == null) {
      return false;
    }
    final PsiClass targetClass = method.getContainingClass();
    if (targetClass == null) {
      return false;
    }
    return AssertHint.isMessageOnFirstPosition(targetClass) || AssertHint.isMessageOnLastPosition(targetClass);
  }
}
