// Data class to store method dependency information
package com.example;

import java.util.List;

public class MethodMetadata {
  public final String packageName;
  public final String className;
  public final String methodName;
  public final List<String> parameterTypes;

  public MethodMetadata(
      String packageName, String className, String methodName, List<String> parameterTypes) {
    this.packageName = packageName;
    this.className = className;
    this.methodName = methodName;
    this.parameterTypes = parameterTypes;
  }

  public String getFullyQualifiedName() {
    return packageName + "." + className + "." + methodName;
  }

  @Override
  public String toString() {
    String params = String.join(", ", parameterTypes);
    return packageName + "." + className + "." + methodName + "(" + params + ")";
  }
}
