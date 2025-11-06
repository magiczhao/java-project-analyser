package com.example;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class CodeExtractor {
  public Map<String, String> analyzeProject(String projectRoot) throws IOException {
    Map<String, String> methodCodeMap = new HashMap<>();

    // We will use JavaParser to walk the directory and parse Java files
    // Assume necessary imports: java.nio.file.*, java.util.*, com.github.javaparser.*
    try {
      Files.walk(java.nio.file.Paths.get(projectRoot))
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(
              javaPath -> {
                try {
                  com.github.javaparser.ast.CompilationUnit cu =
                      new com.github.javaparser.JavaParser()
                          .parse(javaPath)
                          .getResult()
                          .orElse(null);
                  if (cu == null) return;
                  cu.findAll(com.github.javaparser.ast.body.MethodDeclaration.class)
                      .forEach(
                          method -> {
                            String code = null;
                            try {
                              code = getMethodCode(method);
                            } catch (Exception e) {
                              code = "";
                            }
System.out.println("File: " + javaPath + " Method: " + method.getNameAsString());
                            // Build MethodMetadata object for key generation
                            String pkg =
                                cu.getPackageDeclaration()
                                    .map(p -> p.getName().toString())
                                    .orElse("");
                            String clazz =
                                method
                                    .findAncestor(
                                        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
                                            .class)
                                    .map(cls -> cls.getNameAsString())
                                    .orElse("");
                            String methodName = method.getNameAsString();
                            List<String> parameterTypes =
                                method.getParameters().stream()
                                    .map(p -> getFullyQualifiedTypeName(cu, p.getType().asString()))
                                    .collect(Collectors.toList());

                            MethodMetadata methodMetadata = new MethodMetadata(pkg, clazz, methodName, parameterTypes);
                            String key = methodMetadata.toString();
                            methodCodeMap.put(key, code);
                            System.out.println("Key: " + key + " Code: " + code);
                          });
                } catch (Exception e) {
                  // ignore file parse errors
                  System.out.println("Error: " + e.getMessage());
                }
              });
    } catch (IOException e) {
      throw e;
    }

    return methodCodeMap;
  }

  private String getMethodCode(MethodDeclaration method) {
    if (method.getRange().isPresent()) {
      int begin = method.getRange().get().begin.line;
      int end = method.getRange().get().end.line;
      var cuOpt = method.findCompilationUnit();

      if (cuOpt.isPresent()) {
        CompilationUnit cu = cuOpt.get();
        // Get the original source file path
        if (cu.getStorage().isPresent()) {
          try {
            Path sourceFile = cu.getStorage().get().getPath();
            List<String> lines = Files.readAllLines(sourceFile);

            if (begin > 0 && end <= lines.size() && begin <= end) {
              StringBuilder sb = new StringBuilder();
              // Note: begin and end are 1-indexed
              for (int i = begin - 1; i < end; i++) {
                sb.append(lines.get(i));
                if (i != end - 1) sb.append("\n");
              }
              return sb.toString();
            }
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      }
    }
    return method.toString();
  }

  private String getFullyQualifiedTypeName(CompilationUnit cu, String simpleTypeName) {
    // Remove generics for lookup (e.g., "Page<Owner>" -> "Page")
    String baseType = simpleTypeName.replaceAll("<.*>", "").trim();

    // Check if it's a primitive
    if (isPrimitive(baseType)) {
      return simpleTypeName; // Keep as-is for primitives like int, long, etc.
    }

    // Check if it's a java.lang type
    if (isJavaLangType(baseType)) {
      return simpleTypeName.replace(baseType, "java.lang." + baseType);
    }

    // Look through imports for matching type
    for (var importDecl : cu.getImports()) {
      String importName = importDecl.getNameAsString();
      if (importName.endsWith("." + baseType)) {
        // Replace the simple name with the fully qualified one
        return simpleTypeName.replace(baseType, importName);
      }
    }

    // If not found in imports, assume it's in the same package
    String packageName =
        cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

    return packageName.isEmpty() ? simpleTypeName : packageName + "." + simpleTypeName;
  }

  private boolean isPrimitive(String type) {
    return type.matches("byte|short|int|long|float|double|boolean|char|void");
  }

  private boolean isJavaLangType(String type) {
    // Common java.lang types (no import needed)
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
}
