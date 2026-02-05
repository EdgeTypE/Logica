import json
import os
import copy

# Configuration
SOURCE_MODEL = "c:/Users/Çağrı/AppData/Roaming/Hytale/CircuitMod/src/main/resources/Common/Blocks/Circuit/Circuit_Pipe.blockymodel"
OUTPUT_DIR = "c:/Users/Çağrı/AppData/Roaming/Hytale/CircuitMod/src/main/resources/Common/Blocks/Circuit/Wire"
OUTPUT_JSON = "c:/Users/Çağrı/AppData/Roaming/Hytale/CircuitMod/src/main/resources/Server/Item/Items/Circuit/Circuit_Wire.json"

# Group Mapping (Name in Pipe Model -> Bit Position)
# Order: North, South, East, West, Up, Down -> 0, 1, 2, 3, 4, 5
GROUP_MAP = {
    "PipeNorth": 0,
    "PipeSouth": 1,
    "PipeEast": 2,
    "PipeWest": 3,
    "PipeUp": 4,
    "PipeDown": 5
}

# Bitmask: North=1, South=2, East=4, West=8, Up=16, Down=32
# Actually, let's stick to STRING representation for filenames to be clear: "101010"
# Order for string: North, South, East, West, Up, Down

def main():
    if not os.path.exists(OUTPUT_DIR):
        os.makedirs(OUTPUT_DIR)

    with open(SOURCE_MODEL, 'r') as f:
        model_data = json.load(f)

    states_json = {}

    # Hitbox Definitions (0-1 range, based on 32-pixel grid)
    # Center: 11-21
    scale = 32.0
    
    def get_box(min_xyz, max_xyz):
        return {
            "Min": {"x": min_xyz[0]/scale, "y": min_xyz[1]/scale, "z": min_xyz[2]/scale},
            "Max": {"x": max_xyz[0]/scale, "y": max_xyz[1]/scale, "z": max_xyz[2]/scale}
        }

    # Boxes for each component
    # Format: (MinX, MinY, MinZ), (MaxX, MaxY, MaxZ)
    center_box = get_box((11, 11, 11), (21, 21, 21))
    
    # North (Z-): Center Z (11) to 0
    north_box = get_box((11, 11, 0), (21, 21, 11))
    
    # South (Z+): Center Z (21) to 32
    south_box = get_box((11, 11, 21), (21, 21, 32))
    
    # East (X+): Center X (21) to 32
    east_box = get_box((21, 11, 11), (32, 21, 21))
    
    # West (X-): Center X (11) to 0
    west_box = get_box((0, 11, 11), (11, 21, 21))
    
    # Up (Y+): Center Y (21) to 32
    up_box = get_box((11, 21, 11), (21, 32, 21))
    
    # Down (Y-): Center Y (11) to 0
    down_box = get_box((11, 0, 11), (21, 11, 21))

    for i in range(64):
        # Decode bits
        north = (i >> 0) & 1
        south = (i >> 1) & 1
        east =  (i >> 2) & 1
        west =  (i >> 3) & 1
        up =    (i >> 4) & 1
        down =  (i >> 5) & 1
        
        # Suffix: N_S_E_W_U_D (e.g., 100000)
        suffix = f"{north}{south}{east}{west}{up}{down}"
        
        # Create Model Variant (Same as before)
        new_model = copy.deepcopy(model_data)
        
        # Update visibility (Same as before)
        for node in new_model['nodes']:
            name = node['name']
            visible = True
            
            if name == "PipeCenter":
                visible = True
            elif name == "PipeNorth":
                visible = bool(north)
            elif name == "PipeSouth":
                visible = bool(south)
            elif name == "PipeEast":
                visible = bool(east)
            elif name == "PipeWest":
                visible = bool(west)
            elif name == "PipeUp":
                visible = bool(up)
            elif name == "PipeDown":
                visible = bool(down)
            
            node['shape']['settings']['visible'] = visible
            node['shape']['visible'] = visible
            node['name'] = node['name'].replace("Pipe", "Wire")

        # Save Model (Same as before)
        filename = f"Circuit_Wire_{suffix}.blockymodel"
        with open(os.path.join(OUTPUT_DIR, filename), 'w') as f:
            json.dump(new_model, f, indent=2)
            
        # Hitbox Generation
        boxes = [center_box]
        if north: boxes.append(north_box)
        if south: boxes.append(south_box)
        if east: boxes.append(east_box)
        if west: boxes.append(west_box)
        if up: boxes.append(up_box)
        if down: boxes.append(down_box)
        
        # Add to States Definition
        state_key_off = f"Off_{suffix}"
        state_key_on = f"On_{suffix}"
        
        states_json[state_key_off] = {
            "CustomModel": f"Blocks/Circuit/Wire/{filename}",
            "CustomModelTexture": [{"Texture": "BlockTextures/Circuit/Circuit_Wire.png", "Weight": 1}],
            "SelectionBox": boxes,
            "CollisionBox": boxes
        }
        states_json[state_key_on] = {
            "CustomModel": f"Blocks/Circuit/Wire/{filename}",
            "CustomModelTexture": [{"Texture": "BlockTextures/Circuit/Circuit_Wire_On.png", "Weight": 1}],
            "SelectionBox": boxes,
            "CollisionBox": boxes
        }

    # Write to file
    with open("circuit_wire_definitions.json", "w") as f:
        json.dump(states_json, f, indent=4)
    print("Definitions written to circuit_wire_definitions.json")

if __name__ == "__main__":
    main()
