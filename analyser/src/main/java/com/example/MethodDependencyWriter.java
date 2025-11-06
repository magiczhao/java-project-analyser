package com.example;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class MethodDependencyWriter {

  /**
   * Writes the method dependencies to a JSON file.
   *
   * <p>Output structure: { "methodName1": [ "package.class.method(params, ...)", ... ], ... }
   */
  public static void writeAsJson(Map<String, List<MethodMetadata>> dependencyMap, String filePath)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    int methodsCount = 0;
    for (Map.Entry<String, List<MethodMetadata>> entry : dependencyMap.entrySet()) {
      String methodName = entry.getKey();
      List<MethodMetadata> deps = entry.getValue();

      sb.append("  \"").append(escapeJson(methodName)).append("\": [");

      for (int i = 0; i < deps.size(); i++) {
        sb.append("\"").append(escapeJson(deps.get(i).toString())).append("\"");
        if (i < deps.size() - 1) {
          sb.append(", ");
        }
      }
      sb.append("]");
      methodsCount++;
      if (methodsCount < dependencyMap.size()) {
        sb.append(",");
      }
      sb.append("\n");
    }
    sb.append("}\n");

    // Create parent directories if they don't exist
    File file = new File(filePath);
    File parentDir = file.getParentFile();
    if (parentDir != null && !parentDir.exists()) {
      parentDir.mkdirs();
    }

    try (FileWriter writer = new FileWriter(file)) {
      writer.write(sb.toString());
    }
  }

  /**
   * Writes the method dependencies to a YAML file.
   *
   * <p>Output structure: methodName1: - package.class.method(params, ...) - ... methodName2: - ...
   */
  public static void writeAsYaml(Map<String, List<MethodMetadata>> dependencyMap, String filePath)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, List<MethodMetadata>> entry : dependencyMap.entrySet()) {
      String methodName = entry.getKey();
      List<MethodMetadata> deps = entry.getValue();

      sb.append(escapeYamlKey(methodName)).append(":\n");
      for (MethodMetadata dep : deps) {
        sb.append("  - ").append(escapeYamlValue(dep.toString())).append("\n");
      }
    }
    try (FileWriter writer = new FileWriter(filePath)) {
      writer.write(sb.toString());
    }
  }

  // Helper to escape strings for JSON output (rudimentary)
  private static String escapeJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
  }

  // Helper to escape YAML keys (adds quotes if necessary)
  private static String escapeYamlKey(String s) {
    if (s.matches("^[a-zA-Z0-9_.-]+$")) {
      return s;
    } else {
      return "\"" + s.replace("\"", "\"\"") + "\"";
    }
  }

  // Helper to escape YAML values (adds quotes if necessary)
  private static String escapeYamlValue(String s) {
    if (s.matches("^[a-zA-Z0-9_.:\\-(), ]+$")) {
      return s;
    } else {
      return "\"" + s.replace("\"", "\"\"") + "\"";
    }
  }
}
