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
              //   String methodCode = getMethodCode(method);
              //   System.out.println("Method code: " + methodCode);
              List<MethodMetadata> dependencies = new ArrayList<>();

              // Find all method calls in this method
              method
                  .findAll(MethodCallExpr.class)
                  .forEach(
                      call -> {
                        try {
                          MethodMetadata dep = resolveMethodCall(call);
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

  private MethodMetadata resolveMethodCall(MethodCallExpr call) {
    try {
      // Resolve the method call to get full type information
      ResolvedMethodDeclaration resolved = call.resolve();

      // Get the class that declares this method
      String packageName = resolved.getPackageName();
      String className = resolved.getClassName();
      String methodName = resolved.getName();

      // Get parameter types
      List<String> paramTypes = new ArrayList<>();
      for (int i = 0; i < resolved.getNumberOfParams(); i++) {
        ResolvedType paramType = resolved.getParam(i).getType();
        paramTypes.add(paramType.describe());
      }

      return new MethodMetadata(packageName, className, methodName, paramTypes);

    } catch (Exception e) {
      // Enhanced fallback: try to infer context
      String methodName = call.getNameAsString();
      System.err.println(
          "      [FALLBACK] Initial resolve failed for: "
              + methodName
              + " - Scope present: "
              + call.getScope().isPresent()
              + (call.getScope().isPresent()
                  ? " (" + call.getScope().get().getClass().getSimpleName() + ")"
                  : ""));

      // Check if this is a call on 'this' or no explicit scope (likely current class)
      if (!call.getScope().isPresent()) {
        // No scope means it's likely a method in the current class
        return tryResolveInCurrentClass(call);
      }

      // Try to resolve the scope type to get the receiver class
      return tryResolveScopeType(call, methodName);
    }
  }

  private MethodMetadata tryResolveInCurrentClass(MethodCallExpr call) {
    // Find the enclosing class and package
    try {
      var classDecl =
          call.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
      var cuDecl = call.findAncestor(CompilationUnit.class);

      if (classDecl.isPresent() && cuDecl.isPresent()) {
        String packageName =
            cuDecl.get().getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("default");
        String className = classDecl.get().getNameAsString();
        String methodName = call.getNameAsString();

        return new MethodMetadata(packageName, className, methodName, Collections.emptyList());
      }
    } catch (Exception e) {
      // Fall through to unresolved
      System.err.println("        [EXCEPTION in tryResolveInCurrentClass]: " + e.getMessage());
    }

    return new MethodMetadata(
        "UNRESOLVED", "UNRESOLVED", call.getNameAsString(), Collections.emptyList());
  }

  private MethodMetadata tryResolveScopeType(MethodCallExpr call, String methodName) {
    if (!call.getScope().isPresent()) {
      return new MethodMetadata("UNRESOLVED", "UNRESOLVED", methodName, Collections.emptyList());
    }

    try {
      // Try multiple approaches to resolve the scope type
      ResolvedType scopeType = null;

      // Approach 1: Direct type calculation
      try {
        scopeType = call.getScope().get().calculateResolvedType();
        System.err.println(
            "        [SUCCESS] Approach 1 worked for " + methodName + ": " + scopeType.describe());
      } catch (Exception e1) {
        System.err.println(
            "        [APPROACH 1 FAILED] for " + methodName + ": " + e1.getMessage());
        // Approach 2: If scope is a method call, try to resolve that method first
        if (call.getScope().get() instanceof MethodCallExpr) {
          MethodCallExpr scopeMethodCall = (MethodCallExpr) call.getScope().get();
          System.err.println(
              "        [APPROACH 2] Scope is method call: " + scopeMethodCall.getNameAsString());
          try {
            ResolvedMethodDeclaration resolvedScopeMethod = scopeMethodCall.resolve();
            scopeType = resolvedScopeMethod.getReturnType();
            System.err.println("        [SUCCESS] Approach 2 worked: " + scopeType.describe());
          } catch (Exception e2) {
            System.err.println("        [APPROACH 2 FAILED]: " + e2.getMessage());
            // Try string-based extraction for method calls
            MethodMetadata result = tryExtractFromMethodCallAsString(scopeMethodCall, methodName);
            if (result != null) return result;
          }
        } else {
          // Approach 3: Try to infer from variable declaration
          System.err.println("        [APPROACH 3] Trying variable declaration lookup");
          scopeType = tryResolveFromVariableDeclaration(call);
          if (scopeType != null) {
            System.err.println("        [SUCCESS] Approach 3 worked: " + scopeType.describe());
          } else {
            System.err.println("        [APPROACH 3 FAILED]");
            // Approach 4: Try string-based extraction
            MethodMetadata result = tryExtractFromVariableDeclarationAsString(call, methodName);
            if (result != null) return result;
          }
        }
      }

      if (scopeType != null) {
        String scopeTypeName = scopeType.describe();

        // Extract package and class from the fully qualified type
        // Handle generic types like "Page<Vet>" or "List<Vet>"
        String cleanTypeName = scopeTypeName.replaceAll("<.*?>", "");

        int lastDot = cleanTypeName.lastIndexOf('.');
        String packageName = lastDot > 0 ? cleanTypeName.substring(0, lastDot) : "";
        String className = lastDot > 0 ? cleanTypeName.substring(lastDot + 1) : cleanTypeName;

        return new MethodMetadata(packageName, className, methodName, Collections.emptyList());
      }

    } catch (Exception e) {
      System.err.println("        [EXCEPTION in tryResolveScopeType]: " + e.getMessage());
    }

    // Complete fallback
    System.err.println("        [UNRESOLVED] Could not resolve " + methodName);
    return new MethodMetadata("UNRESOLVED", "UNRESOLVED", methodName, Collections.emptyList());
  }

  private ResolvedType tryResolveFromVariableDeclaration(MethodCallExpr call) {
    // If the scope is a simple name expression (variable), look for its declaration
    if (call.getScope().isPresent()
        && call.getScope().get() instanceof com.github.javaparser.ast.expr.NameExpr) {
      com.github.javaparser.ast.expr.NameExpr nameExpr =
          (com.github.javaparser.ast.expr.NameExpr) call.getScope().get();
      String varName = nameExpr.getNameAsString();

      // Find the method containing this call
      var methodDecl = call.findAncestor(MethodDeclaration.class);
      if (methodDecl.isPresent()) {
        // Look for variable declarations in the method
        var varDecls =
            methodDecl.get().findAll(com.github.javaparser.ast.body.VariableDeclarator.class);
        for (var varDecl : varDecls) {
          if (varDecl.getNameAsString().equals(varName) && varDecl.getType() != null) {
            try {
              return varDecl.getType().resolve();
            } catch (Exception e) {
              // Continue searching
            }
          }
        }
      }
    }
    return null;
  }

  private MethodMetadata tryExtractFromVariableDeclarationAsString(
      MethodCallExpr call, String methodName) {
    // Extract type information as strings without full resolution
    if (call.getScope().isPresent()
        && call.getScope().get() instanceof com.github.javaparser.ast.expr.NameExpr) {
      com.github.javaparser.ast.expr.NameExpr nameExpr =
          (com.github.javaparser.ast.expr.NameExpr) call.getScope().get();
      String varName = nameExpr.getNameAsString();

      // FIRST: Check if it's an imported class (for static method calls like
      // SpringApplication.run())
      var cu = call.findAncestor(CompilationUnit.class);
      if (cu.isPresent()) {
        for (var importDecl : cu.get().getImports()) {
          String importName = importDecl.getNameAsString();
          if (importName.endsWith("." + varName)) {
            // This is a static method call on an imported class
            int lastDot = importName.lastIndexOf('.');
            String packageName = importName.substring(0, lastDot);
            String className = importName.substring(lastDot + 1);

            System.err.println(
                "        [SUCCESS APPROACH 4] Found imported class '"
                    + className
                    + "' from package: "
                    + packageName);
            return new MethodMetadata(packageName, className, methodName, Collections.emptyList());
          }
        }
      }

      // SECOND: Check if it's a local variable
      var methodDecl = call.findAncestor(MethodDeclaration.class);
      if (methodDecl.isPresent()) {
        var varDecls =
            methodDecl.get().findAll(com.github.javaparser.ast.body.VariableDeclarator.class);
        for (var varDecl : varDecls) {
          if (varDecl.getNameAsString().equals(varName) && varDecl.getType() != null) {
            String typeString = varDecl.getType().asString();
            String cleanType = typeString.replaceAll("<.*?>", "");
            String packageName = findPackageForType(call, cleanType);

            System.err.println(
                "        [SUCCESS APPROACH 4] Found variable '"
                    + varName
                    + "' type: "
                    + packageName
                    + "."
                    + cleanType);
            return new MethodMetadata(packageName, cleanType, methodName, Collections.emptyList());
          }
        }
      }
    }
    return null;
  }

  private MethodMetadata tryExtractFromMethodCallAsString(
      MethodCallExpr scopeMethodCall, String methodName) {
    // For chained calls, trace back to find the original type

    // First, try to find the root of the call chain
    com.github.javaparser.ast.expr.Expression rootExpr = findRootExpression(scopeMethodCall);

    // If it's a constructor call
    if (rootExpr instanceof com.github.javaparser.ast.expr.ObjectCreationExpr) {
      com.github.javaparser.ast.expr.ObjectCreationExpr constructorCall =
          (com.github.javaparser.ast.expr.ObjectCreationExpr) rootExpr;
      String typeString = constructorCall.getType().asString();
      String cleanType = typeString.replaceAll("<.*?>", "");
      String packageName = findPackageForType(scopeMethodCall, cleanType);

      System.err.println(
          "        [SUCCESS APPROACH 4] Method chain on constructor: "
              + packageName
              + "."
              + cleanType);
      return new MethodMetadata(packageName, cleanType, methodName, Collections.emptyList());
    }

    // If scope is a variable name expression
    if (scopeMethodCall.getScope().isPresent()
        && scopeMethodCall.getScope().get() instanceof com.github.javaparser.ast.expr.NameExpr) {

      com.github.javaparser.ast.expr.NameExpr scopeVar =
          (com.github.javaparser.ast.expr.NameExpr) scopeMethodCall.getScope().get();
      String varName = scopeVar.getNameAsString();

      // Find the variable's type
      var methodDecl = scopeMethodCall.findAncestor(MethodDeclaration.class);
      if (methodDecl.isPresent()) {
        var varDecls =
            methodDecl.get().findAll(com.github.javaparser.ast.body.VariableDeclarator.class);
        for (var varDecl : varDecls) {
          if (varDecl.getNameAsString().equals(varName) && varDecl.getType() != null) {
            String receiverType = varDecl.getType().asString().replaceAll("<.*?>", "");
            String receiverPackage = findPackageForType(scopeMethodCall, receiverType);

            System.err.println(
                "        [SUCCESS APPROACH 4] Method call on "
                    + receiverPackage
                    + "."
                    + receiverType);
            // Assume collection methods return collections (heuristic)
            if (scopeMethodCall.getNameAsString().toLowerCase().contains("list")) {
              return new MethodMetadata("java.util", "List", methodName, Collections.emptyList());
            }
            // For builder pattern, assume fluent methods return the same type
            return new MethodMetadata(
                receiverPackage, receiverType, methodName, Collections.emptyList());
          }
        }
      }
    }
    return null;
  }

  // Add this helper method to find the root of a call chain
  private com.github.javaparser.ast.expr.Expression findRootExpression(
      com.github.javaparser.ast.expr.Expression expr) {
    if (expr instanceof MethodCallExpr) {
      MethodCallExpr methodCall = (MethodCallExpr) expr;
      if (methodCall.getScope().isPresent()) {
        return findRootExpression(methodCall.getScope().get());
      }
    }
    return expr;
  }

  private String findPackageForType(MethodCallExpr call, String typeName) {
    // Find the compilation unit to check imports
    var cu = call.findAncestor(CompilationUnit.class);
    if (cu.isPresent()) {
      // Check imports
      for (var importDecl : cu.get().getImports()) {
        String importName = importDecl.getNameAsString();
        if (importName.endsWith("." + typeName)) {
          // Extract package (everything before the last dot)
          int lastDot = importName.lastIndexOf('.');
          return importName.substring(0, lastDot);
        }
      }

      // If not in imports, assume same package
      return cu.get().getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("default");
    }

    return "UNKNOWN";
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
                    return p.getType().asString();
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

    // System.out.println("Found " + javaFiles.size() + " Java files\n");
    Map<String, List<MethodMetadata>> allDependencies = new HashMap<>();
    for (Path javaFile : javaFiles) {
      try {
        System.out.println("Analyzing: " + javaFile);
        Map<String, List<MethodMetadata>> dependencies = analyzeFile(javaFile.toString());
        allDependencies.putAll(dependencies);
      } catch (Exception e) {
        System.err.println("  Error analyzing: " + e.getMessage());
      }
    }
    return allDependencies;
  }
}
