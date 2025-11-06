import json
from anthropic import Anthropic
import dotenv
import os
dotenv.load_dotenv()
ANTHROPIC_API_KEY = os.getenv("ANTHROPIC_API_KEY")
client = Anthropic(api_key=ANTHROPIC_API_KEY)

class TestCaseGenerator:
    def __init__(self):
        self.client = client
        self.template = open("api_template.txt", "r").read()

    def generate_test_case(self, api_name: str, api_code: str, dependencies: dict):
        prompt = self.template.replace("{{api_name}}", api_name).replace("{{api_code}}", api_code).replace("{{dependencies}}", json.dumps(dependencies))
        return self.client.messages.create(model="claude-haiku-4-5-20251001",
            messages=[{"role": "user", "content": prompt}],
            max_tokens=10000)

class CodeIndexer:
    def __init__(self, file_path: str):
        with open(file_path, 'r') as f:
            self.code_index = json.load(f)

    def has_code(self, function_name: str):
        return function_name in self.code_index

    def get_code(self, function_name: str):
        return self.code_index[function_name]

    def get_all_codes(self):
        return self.code_index

    def get_all_functions(self):
        return list(self.code_index.keys())

class DependencyIndexer:
    def __init__(self, file_path: str):
        with open(file_path, 'r') as f:
            self.dependency_index = json.load(f)

    def get_dependencies(self, function_name: str):
        return self.dependency_index[function_name]

    def get_all_dependencies(self):
        return self.dependency_index
    
    def get_all_functions(self):
        return list(self.dependency_index.keys())

class APIIndexer:
    def __init__(self, file_path: str):
        with open(file_path, 'r') as f:
            self.api_index = json.load(f)

    def get_all_apis(self):
        return self.api_index

def get_dependencies_code(api: str, code_indexer: CodeIndexer, dependency_indexer: DependencyIndexer):
    related_map = {}
    dependencies = dependency_indexer.get_dependencies(api)
    for dependency in dependencies:
        if code_indexer.has_code(dependency):
            related_map[dependency] = code_indexer.get_code(dependency)
    return related_map

if __name__ == "__main__":
    code_indexer = CodeIndexer("../output/code.json")
    dependency_indexer = DependencyIndexer("../output/dependencies.json")
    api_indexer = APIIndexer("../output/api_endpoints.json")
    api = "org.springframework.samples.petclinic.vet.VetController.showVetList(int, org.springframework.ui.Model)"
    api_code = code_indexer.get_code(api)
    dependencies_code = get_dependencies_code(api, code_indexer, dependency_indexer)
    print(f"API code: {api_code}")
    print(json.dumps(dependencies_code, indent=4))
    test_case_generator = TestCaseGenerator()
    test_case = test_case_generator.generate_test_case(api, api_code, dependencies_code)
    print("test case:")
    print(test_case.content[0].text)
