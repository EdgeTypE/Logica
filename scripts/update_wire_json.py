import json
import os

WIRE_JSON_PATH = "src/main/resources/Server/Item/Items/Circuit/Circuit_Wire.json"
DEFINITIONS_JSON_PATH = "circuit_wire_definitions.json"

def main():
    if not os.path.exists(WIRE_JSON_PATH) or not os.path.exists(DEFINITIONS_JSON_PATH):
        print("Files not found")
        return

    with open(WIRE_JSON_PATH, 'r') as f:
        wire_data = json.load(f)

    with open(DEFINITIONS_JSON_PATH, 'r') as f:
        definitions = json.load(f)

    # Update definitions
    if "BlockType" in wire_data and "State" in wire_data["BlockType"]:
        wire_data["BlockType"]["State"]["Definitions"] = definitions
        print("Updated Definitions")
    else:
        print("Invalid structure in Circuit_Wire.json")
        return

    with open(WIRE_JSON_PATH, 'w') as f:
        json.dump(wire_data, f, indent=4)
    print("Successfully wrote Circuit_Wire.json")

if __name__ == "__main__":
    main()
