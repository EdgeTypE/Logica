import json
import os

OUTPUT_FILE = r"c:\Users\Çağrı\AppData\Roaming\Hytale\CircuitMod\src\main\resources\Server\Item\Items\Circuit\Circuit_Wire.json"

definitions = {}

# Order: Back (South), Right (East), Front (North), Left (West)
# 4 bits: B R F L
for i in range(16):
    binary_str = format(i, '04b')
    
    # OFF State
    definitions[f"Off_{binary_str}"] = {
        "CustomModel": f"Blocks/Circuit/Wire/Circuit_Wire_{binary_str}.blockymodel",
        "CustomModelTexture": [{"Texture": "BlockTextures/Circuit/Circuit_Wire.png", "Weight": 1}],
        "Textures": [{"All": "BlockTextures/Circuit/Circuit_Wire.png", "Weight": 1}]
    }
    
    # ON State
    definitions[f"On_{binary_str}"] = {
        "CustomModel": f"Blocks/Circuit/Wire/Circuit_Wire_{binary_str}.blockymodel",
        "CustomModelTexture": [{"Texture": "BlockTextures/Circuit/Circuit_Wire_On.png", "Weight": 1}],
        "Textures": [{"All": "BlockTextures/Circuit/Circuit_Wire_On.png", "Weight": 1}]
    }

# Also add default On/Off as fallbacks (mapped to 0000 or disconnected)
definitions["Off"] = definitions["Off_0000"]
definitions["On"] = definitions["On_0000"]
definitions["default"] = definitions["Off_0000"] # Fallback

circuit_wire_json = {
    "TranslationProperties": {
        "Name": "server.items.Circuit_Wire.name"
    },
    "MaxStack": 64,
    "Icon": "Icons/Items/Circuit/Circuit_Wire.png",
    "Categories": [
        "Blocks.Circuit"
    ],
    "PlayerAnimationsId": "Block",
    "BlockType": {
        "Material": "Solid",
        "DrawType": "Model", # Changed from Cube to Model
        "CustomModel": "Blocks/Circuit/Wire/Circuit_Wire_0000.blockymodel", # Default model
         "CustomModelTexture": [
            {
                "Texture": "BlockTextures/Circuit/Circuit_Wire.png",
                "Weight": 1
            }
        ],
        "Flags": {
            "IsUsable": True
        },
        "Gathering": {
            "Soft": {}
        },
        "BlockParticleSetId": "Stone",
        "Textures": [
            {
                "All": "BlockTextures/Circuit/Circuit_Wire.png",
                "Weight": 1
            }
        ],
        "BlockSoundSetId": "Metal",
        "Interactions": {
            "Use": {
                "Interactions": [
                    {
                        "Type": "ChangeState",
                        "Changes": {
                            "default": "Off",
                            "On": "Off",
                            "Off": "On"
                            # Note: Simple toggle might not work well with directional states if client predicts locally only based on this.
                            # But valid for debugging.
                        }
                    }
                ]
            }
        },
        "State": {
            "Definitions": definitions
        }
    },
    "Tags": {
        "Type": [
            "Circuit"
        ]
    },
    "ItemSoundSetId": "ISS_Items_Metal"
}

with open(OUTPUT_FILE, 'w') as f:
    json.dump(circuit_wire_json, f, indent=4)

print("Generated Circuit_Wire.json")
