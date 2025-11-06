package com.example;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class DependencyAnalyzer {

  private JavaSymbolSolver symbolSolver;
  private String outputDir;

  public DependencyAnalyzer(String projectSourceRoot, List<String> externalJars, String outputDir)
      throws IOException {
    this.outputDir = outputDir;
    // Setup type solvers
    CombinedTypeSolver typeSolver = new CombinedTypeSolver();

    // Add JDK classes
    typeSolver.add(new ReflectionTypeSolver());

    // Add project source code
    typeSolver.add(new JavaParserTypeSolver(new File(projectSourceRoot)));

    // Add external JARs (Spring, etc.)
    if (externalJars != null) {
      for (String jarPath : externalJars) {
        try {
          typeSolver.add(new JarTypeSolver(jarPath));
          System.out.println("Added JAR: " + jarPath);
        } catch (IOException e) {
          System.err.println("Failed to add JAR: " + jarPath + " - " + e.getMessage());
        }
      }
    }

    // Configure symbol solver
    symbolSolver = new JavaSymbolSolver(typeSolver);
    StaticJavaParser.getConfiguration().setSymbolResolver(symbolSolver);
  }

  public Map<String, List<MethodMetadata>> analyzeFile(String javaFilePath) throws IOException {
    File file = new File(javaFilePath);
    CompilationUnit cu = StaticJavaParser.parse(file);

    Map<String, List<MethodMetadata>> methodDependencies = new HashMap<>();

    // Find all methods in the file
    cu.findAll(MethodDeclaration.class)
        .forEach(
            method -> {
              String methodName = getMethodFullName(cu, method);
              List<MethodMetadata> dependencies = new ArrayList<>();

              // Find all method calls in this method
              method
                  .findAll(MethodCallExpr.class)
                  .forEach(
                      call -> {
                        try {
                          MethodMetadata dep = resolveMethodCall(cu, call);
                          dependencies.add(dep);
                        } catch (Exception e) {
                          // If resolution fails, store what we know
                          System.err.println(
                              "    [ERROR] Failed to resolve: "
                                  + call.getNameAsString()
                                  + " in method "
                                  + method.getNameAsString()
                                  + " - "
                                  + e.getMessage());
                          dependencies.add(
                              new MethodMetadata(
                                  "UNRESOLVED",
                                  "UNRESOLVED",
                                  call.getNameAsString(),
                                  Collections.emptyList()));
                        }
                      });

              methodDependencies.put(methodName, dependencies);
            });

    return methodDependencies;
  }

  private MethodMetadata resolveMethodCall(CompilationUnit cu, MethodCallExpr call) {
    // Try full resolution first
    try {
      ResolvedMethodDeclaration resolved = call.resolve();
      String packageName = resolved.getPackageName();
      String className = resolved.getClassName();
      String methodName = resolved.getName();

      List<String> paramTypes = new ArrayList<>();
      for (int i = 0; i < resolved.getNumberOfParams(); i++) {
        ResolvedType paramType = resolved.getParam(i).getType();
        paramTypes.add(paramType.describe());
      }

      return new MethodMetadata(packageName, className, methodName, paramTypes);
    } catch (Exception e) {
      // Fallback to AST-based extraction
      return extractMethodMetadataFromAST(cu, call);
    }
  }

  private MethodMetadata extractMethodMetadataFromAST(CompilationUnit cu, MethodCallExpr call) {
    String methodName = call.getNameAsString();
    
    // Try to extract scope type
    if (!call.getScope().isPresent()) {
      // No scope - method in current class
      return getMethodInCurrentClass(cu, call, methodName);
    }

    // Has scope - try to determine the type
    String scopeType = extractScopeType(cu, call);
    
    if (scopeType != null && !scopeType.equals("UNRESOLVED")) {
      // Parse the fully qualified type name
      String cleanType = scopeType.replaceAll("<.*?>", "");
      int lastDot = cleanType.lastIndexOf('.');
      String packageName = lastDot > 0 ? cleanType.substring(0, lastDot) : "";
      String className = lastDot > 0 ? cleanType.substring(lastDot + 1) : cleanType;
      
      return new MethodMetadata(packageName, className, methodName, Collections.emptyList());
    }

    return new MethodMetadata("UNRESOLVED", "UNRESOLVED", methodName, Collections.emptyList());
  }

  private MethodMetadata getMethodInCurrentClass(CompilationUnit cu, MethodCallExpr call, String methodName) {
    var classDecl = call.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
    
    if (classDecl.isPresent()) {
      String packageName = cu.getPackageDeclaration()
          .map(pd -> pd.getNameAsString())
          .orElse("default");
      String className = classDecl.get().getNameAsString();
      
      return new MethodMetadata(packageName, className, methodName, Collections.emptyList());
    }
    
    return new MethodMetadata("UNRESOLVED", "UNRESOLVED", methodName, Collections.emptyList());
  }

  private String extractScopeType(CompilationUnit cu, MethodCallExpr call) {
    if (!call.getScope().isPresent()) {
      return null;
    }

    var scope = call.getScope().get();

    // Try to resolve the scope type directly
    try {
      ResolvedType scopeType = scope.calculateResolvedType();
      return scopeType.describe();
    } catch (Exception e) {
      // Fallback to string-based extraction
    }

    // Check if scope is a MethodCallExpr (chained calls)
    if (scope instanceof MethodCallExpr) {
      MethodCallExpr scopeMethod = (MethodCallExpr) scope;
      try {
        ResolvedMethodDeclaration resolved = scopeMethod.resolve();
        return resolved.getReturnType().describe();
      } catch (Exception e) {
        // Try to infer return type from method name patterns
        String returnType = inferReturnTypeFromMethodName(cu, scopeMethod);
        if (returnType != null && !returnType.equals("UNRESOLVED")) {
          return returnType;
        }
        // For chained calls, trace back to the root
        return extractScopeType(cu, scopeMethod);
      }
    }

    // Check if scope is a NameExpr (variable or class name)
    if (scope instanceof com.github.javaparser.ast.expr.NameExpr) {
      com.github.javaparser.ast.expr.NameExpr nameExpr = 
          (com.github.javaparser.ast.expr.NameExpr) scope;
      String name = nameExpr.getNameAsString();
      
      // Check if it's an imported class (static method call)
      String importedType = findTypeInImports(cu, name);
      if (importedType != null) {
        return importedType;
      }
      
      // Check if it's a local variable
      String varType = findVariableType(cu, call, name);
      if (varType != null) {
        return varType;
      }
    }

    // Check if scope is a constructor call
    if (scope instanceof com.github.javaparser.ast.expr.ObjectCreationExpr) {
      com.github.javaparser.ast.expr.ObjectCreationExpr constructor = 
          (com.github.javaparser.ast.expr.ObjectCreationExpr) scope;
      String typeString = constructor.getType().asString();
      return getFullyQualifiedTypeName(cu, typeString);
    }

    return "UNRESOLVED";
  }

  private String inferReturnTypeFromMethodName(CompilationUnit cu, MethodCallExpr methodCall) {
    String methodName = methodCall.getNameAsString();
    
    // Common getter patterns that return collections
    if (methodName.matches("get.*List") || methodName.equals("toList") || 
        methodName.matches("as.*List") || methodName.matches(".*ToList")) {
      return "java.util.List";
    }
    if (methodName.matches("get.*Set") || methodName.equals("toSet") || 
        methodName.matches("as.*Set")) {
      return "java.util.Set";
    }
    if (methodName.matches("get.*Map") || methodName.equals("toMap") || 
        methodName.matches("as.*Map")) {
      return "java.util.Map";
    }
    if (methodName.matches("get.*Collection") || methodName.equals("toCollection")) {
      return "java.util.Collection";
    }
    
    return null;
  }

  private String findTypeInImports(CompilationUnit cu, String typeName) {
    for (var importDecl : cu.getImports()) {
      String importName = importDecl.getNameAsString();
      if (importName.endsWith("." + typeName)) {
        return importName;
      }
    }
    return null;
  }

  private String findVariableType(CompilationUnit cu, MethodCallExpr call, String varName) {
    var methodDecl = call.findAncestor(MethodDeclaration.class);
    if (methodDecl.isPresent()) {
      var varDecls = methodDecl.get().findAll(com.github.javaparser.ast.body.VariableDeclarator.class);
      for (var varDecl : varDecls) {
        if (varDecl.getNameAsString().equals(varName) && varDecl.getType() != null) {
          String typeString = varDecl.getType().asString();
          return getFullyQualifiedTypeName(cu, typeString);
        }
      }
    }
    return null;
  }

  private String getFullyQualifiedTypeName(CompilationUnit cu, String simpleTypeName) {
    // Remove generics for lookup
    String baseType = simpleTypeName.replaceAll("<.*>", "").trim();

    // Check if it's a primitive
    if (isPrimitive(baseType)) {
      return simpleTypeName;
    }

    // Check if it's a java.lang type
    if (isJavaLangType(baseType)) {
      return simpleTypeName.replace(baseType, "java.lang." + baseType);
    }

    // Look through imports
    String importedType = findTypeInImports(cu, baseType);
    if (importedType != null) {
      return simpleTypeName.replace(baseType, importedType);
    }

    // If not found in imports, assume it's in the same package
    String packageName = cu.getPackageDeclaration()
        .map(pd -> pd.getNameAsString())
        .orElse("");

    return packageName.isEmpty() ? simpleTypeName : packageName + "." + simpleTypeName;
  }

  private boolean isPrimitive(String type) {
    return type.matches("byte|short|int|long|float|double|boolean|char|void");
  }

  private boolean isJavaLangType(String type) {
    return type.equals("String")
        || type.equals("Object")
        || type.equals("Integer")
        || type.equals("Long")
        || type.equals("Double")
        || type.equals("Float")
        || type.equals("Short")
        || type.equals("Byte")
        || type.equals("Character")
        || type.equals("Boolean")
        || type.equals("Number")
        || type.equals("Class")
        || type.equals("Void");
  }

  private String getMethodFullName(CompilationUnit cu, MethodDeclaration method) {
    String packageName =
        cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("default");

    String className =
        method
            .findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
            .map(c -> c.getNameAsString())
            .orElse("Unknown");

    String methodName = method.getNameAsString();

    // Get parameter types
    String params =
        method.getParameters().stream()
            .map(
                p -> {
                  try {
                    return p.getType().resolve().describe();
                  } catch (Exception e) {
                    // Fallback to simple name if resolution fails
                    return getFullyQualifiedTypeName(cu, p.getType().asString());
                  }
                })
            .collect(Collectors.joining(", "));

    return packageName + "." + className + "." + methodName + "(" + params + ")";
  }

  public Map<String, List<MethodMetadata>> analyzeProject(String projectRoot) throws IOException {
    Path rootPath = Paths.get(projectRoot);

    List<Path> javaFiles =
        Files.walk(rootPath)
            .filter(p -> p.toString().endsWith(".java"))
            .collect(Collectors.toList());

    Map<String, List<MethodMetadata>> allDependencies = new HashMap<>();
    for (Path javaFile : javaFiles) {
      try {
        // System.out.println("Analyzing: " + javaFile);
        Map<String, List<MethodMetadata>> dependencies = analyzeFile(javaFile.toString());
        allDependencies.putAll(dependencies);
      } catch (Exception e) {
        System.err.println("  Error analyzing: " + e.getMessage());
      }
    }
    return allDependencies;
  }
}
