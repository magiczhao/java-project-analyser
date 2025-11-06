package com.example;

public class APIEndpoint {
        public MethodMetadata methodMetadata;
        public String httpVerb;
        public String methodPath;
        public String classMapping;

        public APIEndpoint(MethodMetadata methodMetadata, String httpVerb, String methodPath, String classMapping) {
            this.methodMetadata = methodMetadata;
            this.httpVerb = httpVerb;
            this.methodPath = methodPath;
            this.classMapping = classMapping;
        }

        @Override
        public String toString() {
            String path = (classMapping == null || classMapping.isEmpty()) ?
                    methodPath : (classMapping.replaceAll("/$", "") + "/" + methodPath.replaceAll("^/", ""));
            return String.format("%s %s (%s)", httpVerb, path, methodMetadata);
        }

        public String toJson() {
            return "{" +
                    "\"methodMetadata\": \"" + methodMetadata + "\"," +
                    "\"httpVerb\": \"" + httpVerb + "\"," +
                    "\"methodPath\": \"" + methodPath + "\"," +
                    "\"classMapping\": \"" + classMapping + "\"" +
                    "}";
        }
    }