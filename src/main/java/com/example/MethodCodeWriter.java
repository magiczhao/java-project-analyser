package com.example;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;

public class MethodCodeWriter {

  /**
   * Writes a mapping from "method metadata" string keys to code string values as JSON.
   *
   * Output Example:
   * {
   *   "package.class.method(params, ...)": "public void foo() {...}",
   *   ...
   * }
   */
  public static void writeAsJson(Map<String, String> codeMap, String filePath) throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    int count = 0;
    for (Map.Entry<String, String> entry : codeMap.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      sb.append("  \"")
        .append(escapeJson(key))
        .append("\": \"")
        .append(escapeJson(value))
        .append("\"");
      count++;
      if (count < codeMap.size()) {
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

  // Helper to escape JSON strings
  private static String escapeJson(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "");
  }
}
