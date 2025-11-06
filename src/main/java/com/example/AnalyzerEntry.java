package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AnalyzerEntry {
  public static void main(String[] args) {
    if (args.length < 1) {
      System.out.println(
          "Usage: java DependencyAnalyzer <project-source-root> [external-jar1] [external-jar2] ...");
      System.out.println("\nExample:");
      System.out.println(
          "  java DependencyAnalyzer /path/to/spring-petclinic/src/main/java spring-web.jar spring-data-commons.jar /path/to/output");
      return;
    }

    String projectRoot = args[0];
    String outputDir = args[1];
    List<String> externalJars = new ArrayList<>();

    // Collect external JARs from command line
    for (int i = 1; i < args.length; i++) {
      externalJars.add(args[i]);
    }
    System.out.println("AnalyzerEntry: " + projectRoot + " " + outputDir + " " + externalJars);
    // extract dependencies
    try {
      DependencyAnalyzer analyzer = new DependencyAnalyzer(projectRoot, externalJars, outputDir);
      Map<String, List<MethodMetadata>> dependencies = analyzer.analyzeProject(projectRoot);
      MethodDependencyWriter.writeAsJson(dependencies, outputDir + "/dependencies.json");
    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
      e.printStackTrace();
    }
    // extract code
    try {
      CodeExtractor extractor = new CodeExtractor();
      Map<String, String> codeMap = extractor.analyzeProject(projectRoot);
      MethodCodeWriter.writeAsJson(codeMap, outputDir + "/code.json");
    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
      e.printStackTrace();
    }

    // extract API endpoints
    try {
      APIExtractor extractor = new APIExtractor();
      List<APIEndpoint> apiEndpoints = extractor.analyseProject(projectRoot);
      APIEndpointWriter.writeAsJson(apiEndpoints, outputDir + "/api_endpoints.json");
    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
      e.printStackTrace();
    }
  }
}
