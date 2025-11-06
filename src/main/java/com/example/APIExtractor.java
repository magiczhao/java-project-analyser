package com.example;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Extracts all Spring Boot API endpoint methods from a project directory.
 * Each API contains a MethodMetadata, httpVerb, methodPath, and classMapping.
 */
public class APIExtractor {

    // Annotations indicating a Spring controller class
    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of(
            "RestController", "Controller");

    // Annotations indicating mapping on a method
    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");

    /**
     * Analyse the given project root directory, and extract all API endpoints.
     * @param rootDir the project directory
     * @return a list of APIEndpoint, each with a MethodMetadata
     * @throws IOException
     */
    public List<APIEndpoint> analyseProject(String rootDir) throws IOException {
        List<APIEndpoint> endpoints = new ArrayList<>();
        List<File> javaFiles = findJavaFiles(rootDir);
        for (File javaFile : javaFiles) {
            endpoints.addAll(extractFromFile(javaFile));
        }
        return endpoints;
    }

    /** Find all .java files in a directory recursively */
    private List<File> findJavaFiles(String rootDir) throws IOException {
        List<File> files = new ArrayList<>();
        Files.walk(Paths.get(rootDir))
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> files.add(path.toFile()));
        return files;
    }

    /**
     * Extract endpoints from a single Java source file.
     */
    private List<APIEndpoint> extractFromFile(File javaFile) {
        List<APIEndpoint> endpoints = new ArrayList<>();
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(javaFile);
        } catch (Exception e) {
            // ignore unparseable files
            return endpoints;
        }

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            if (!isController(clazz)) return;

            String className = clazz.getNameAsString();
            String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            String classMapping = extractClassLevelMapping(clazz);

            clazz.findAll(MethodDeclaration.class).forEach(method -> {
                String httpVerb = null;
                String methodPath = null;

                for (AnnotationExpr ann : method.getAnnotations()) {
                    String annName = ann.getName().getIdentifier();
                    if (MAPPING_ANNOTATIONS.contains(annName)) {
                        httpVerb = deriveHttpVerb(annName, ann);
                        methodPath = extractPathValue(ann);
                        break;
                    }
                }
                // If method has no mapping annotation, skip it
                if (httpVerb == null) return;
                if (methodPath == null) methodPath = "";
                List<String> paramTypes = new ArrayList<>();
                method.getParameters().forEach(param -> 
                    paramTypes.add(getFullyQualifiedTypeName(cu, param.getType().asString())));
                MethodMetadata methodMetadata = new MethodMetadata(
                        packageName,
                        className,
                        method.getNameAsString(),
                        paramTypes
                );
                endpoints.add(
                        new APIEndpoint(methodMetadata, httpVerb, methodPath, classMapping));
            });
        });
        return endpoints;
    }

    private boolean isController(ClassOrInterfaceDeclaration clazz) {
        for (AnnotationExpr ann : clazz.getAnnotations()) {
            String name = ann.getName().getIdentifier();
            if (CONTROLLER_ANNOTATIONS.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extract the path from a class-level @RequestMapping
     */
    private String extractClassLevelMapping(ClassOrInterfaceDeclaration clazz) {
        for (AnnotationExpr ann : clazz.getAnnotations()) {
            String name = ann.getName().getIdentifier();
            if (name.equals("RequestMapping")) {
                String val = extractPathValue(ann);
                if (val != null) return val;
            }
        }
        return "";
    }

    /**
     * Extracts the value of the path from a mapping annotation.
     */
    private String extractPathValue(AnnotationExpr ann) {
        String val = null;
        try {
            if (ann.isSingleMemberAnnotationExpr()) {
                val = ann.asSingleMemberAnnotationExpr().getMemberValue().toString();
            } else if (ann.isNormalAnnotationExpr()) {
                var norm = ann.asNormalAnnotationExpr();
                for (var pair : norm.getPairs()) {
                    if (pair.getNameAsString().equals("path") || pair.getNameAsString().equals("value")) {
                        val = pair.getValue().toString();
                        break;
                    }
                }
            }
        } catch (Exception ignore) {}
        if (val != null) {
            val = val.replaceAll("^\"|\"$", ""); // Remove quotes
        }
        return val;
    }

    /**
     * Maps annotation name and value to HTTP VERB
     */
    private String deriveHttpVerb(String annName, AnnotationExpr ann) {
        switch (annName) {
            case "GetMapping": return "GET";
            case "PostMapping": return "POST";
            case "PutMapping": return "PUT";
            case "DeleteMapping": return "DELETE";
            case "PatchMapping": return "PATCH";
            case "RequestMapping":
                // Check method attribute
                try {
                    if (ann.isNormalAnnotationExpr()) {
                        var norm = ann.asNormalAnnotationExpr();
                        for (var pair : norm.getPairs()) {
                            if (pair.getNameAsString().equals("method")) {
                                var value = pair.getValue().toString();
                                // value might be RequestMethod.GET, or array
                                if (value.contains("GET")) return "GET";
                                if (value.contains("POST")) return "POST";
                                if (value.contains("PUT")) return "PUT";
                                if (value.contains("DELETE")) return "DELETE";
                                if (value.contains("PATCH")) return "PATCH";
                            }
                        }
                    }
                } catch (Exception ignore) {}
                return "ALL";
            default: return annName.replace("Mapping", "").toUpperCase();
        }
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

