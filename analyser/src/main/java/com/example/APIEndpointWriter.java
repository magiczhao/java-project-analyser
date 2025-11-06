package com.example;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * Utility class to write a list of APIEndpoint objects into a JSON file.
 */
public class APIEndpointWriter {

    /**
     * Write a list of APIEndpoint objects to a JSON file.
     * @param apiEndpoints the list of endpoints
     * @param outputFile output file path
     * @throws IOException
     */
    public static void writeAsJson(List<APIEndpoint> apiEndpoints, String outputFile) throws IOException {
        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write("[\n");
            for (int i = 0; i < apiEndpoints.size(); i++) {
                writer.write(apiEndpoints.get(i).toJson());
                if (i < apiEndpoints.size() - 1) {
                    writer.write(",\n");
                }
            }
            writer.write("\n]");
        }
    }
}
