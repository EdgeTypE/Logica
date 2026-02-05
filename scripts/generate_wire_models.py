import json
import os
import copy

# Paths
SOURCE_MODEL = r"c:\Users\Çağrı\AppData\Roaming\Hytale\CircuitMod\src\main\resources\Common\Blocks\Circuit\Circuit_Wire.blockymodel"
OUTPUT_DIR = r"c:\Users\Çağrı\AppData\Roaming\Hytale\CircuitMod\src\main\resources\Common\Blocks\Circuit\Wire"

# Ensure output directory exists
os.makedirs(OUTPUT_DIR, exist_ok=True)

# Groups mapping
# Order: Back (South), Right (East), Front (North), Left (West)
# BUT wait, the java code does: (hasBack ? "1" : "0") + (hasRight ? "1" : "0") + (hasFront ? "1" : "0") + (hasLeft ? "1" : "0")
# So it's Back, Right, Front, Left.
# Let's verify the group names in blockymodel.
# PipeSouth (Back), PipeEast (Right), PipeNorth (Front), PipeWest (Left)

GROUPS = ["PipeSouth", "PipeEast", "PipeNorth", "PipeWest"]

try:
    with open(SOURCE_MODEL, 'r') as f:
        base_model = json.load(f)
except FileNotFoundError:
    print(f"Error: Could not find source model at {SOURCE_MODEL}")
    exit(1)

def set_group_visibility(model, visibility_map):
    """
    visibility_map: dict of group_name -> bool
    """
    for node in model.get("nodes", []):
        name = node.get("name")
        if name in visibility_map:
            # Check if "shape" exists and set visible there, or on the node itself if it's a group
            # blockymodel structure: node has "shape" -> "settings" -> "visible" usually? 
            # Or "shape" -> "visible"
            # Looking at the file content provided previously:
            # "shape": { ..., "visible": true, ... }
            if "shape" in node:
                node["shape"]["visible"] = visibility_map[name]

for i in range(16):
    # Format i as 4-bit binary string: e.g., "0101"
    # The order in Java: Back, Right, Front, Left
    # So index 0 is Back, 1 is Right, 2 is Front, 3 is Left
    binary_str = format(i, '04b')
    
    visibility = {
        "PipeCenter": True, # Always visible
        "PipeSouth": binary_str[0] == '1', # Back
        "PipeEast": binary_str[1] == '1',  # Right
        "PipeNorth": binary_str[2] == '1', # Front
        "PipeWest": binary_str[3] == '1'   # Left
    }
    
    current_model = copy.deepcopy(base_model)
    set_group_visibility(current_model, visibility)
    
    filename = f"Circuit_Wire_{binary_str}.blockymodel"
    output_path = os.path.join(OUTPUT_DIR, filename)
    
    with open(output_path, 'w') as f:
        json.dump(current_model, f, indent=2)
    
    print(f"Generated {filename}")

print("Done generating 16 models.")
