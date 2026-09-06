from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

from ultralytics import YOLO


def prepare_onnx_for_nnapi(model_path: Path) -> int:
    """Add Split metadata required by ONNX Runtime's NNAPI execution provider."""
    import onnx
    from onnx import helper, numpy_helper

    model = onnx.load(model_path)
    initializers = {
        initializer.name: numpy_helper.to_array(initializer).tolist()
        for initializer in model.graph.initializer
    }
    patched = 0
    replaced_split_inputs: set[str] = set()
    for node in model.graph.node:
        if node.op_type != "Split" or len(node.input) < 2:
            continue
        split_sizes = initializers.get(node.input[1])
        has_num_outputs = any(attribute.name == "num_outputs" for attribute in node.attribute)
        if split_sizes and len(set(split_sizes)) == 1:
            changed = False
            if len(node.input) > 1:
                replaced_split_inputs.update(node.input[1:])
                del node.input[1:]
                changed = True
            if not has_num_outputs:
                node.attribute.append(helper.make_attribute("num_outputs", len(node.output)))
                changed = True
            if changed:
                patched += 1

    if patched:
        used_inputs = {input_name for node in model.graph.node for input_name in node.input}
        retained_initializers = [
            initializer
            for initializer in model.graph.initializer
            if initializer.name not in replaced_split_inputs or initializer.name in used_inputs
        ]
        del model.graph.initializer[:]
        model.graph.initializer.extend(retained_initializers)
        onnx.checker.check_model(model)
        onnx.save(model, model_path)
    return patched


def make_litert_shapes_static(model_path: Path) -> int:
    """Replace dynamic shape signatures with known fixed shapes for GPU delegation."""
    import flatbuffers
    from ai_edge_litert import schema_py_generated as schema

    model = schema.Model.GetRootAsModel(model_path.read_bytes(), 0)
    mutable_model = schema.ModelT.InitFromObj(model)
    patched = 0
    for subgraph in mutable_model.subgraphs:
        for tensor in subgraph.tensors:
            signature = list(tensor.shapeSignature) if tensor.shapeSignature is not None else []
            if signature and any(dimension < 0 for dimension in signature):
                tensor.shapeSignature = list(tensor.shape)
                patched += 1

    if patched:
        builder = flatbuffers.Builder(0)
        offset = mutable_model.Pack(builder)
        builder.Finish(offset, file_identifier=b"TFL3")
        model_path.write_bytes(bytes(builder.Output()))
    return patched


def export_litert(model_path: Path, image_size: int, precision: str) -> Path:
    if precision == "int8":
        raise ValueError("LiteRT INT8 export is disabled until a representative calibration set is available")

    if model_path.suffix.lower() == ".onnx":
        onnx_path = model_path
    else:
        onnx_path = Path(
            YOLO(str(model_path)).export(
                format="onnx",
                imgsz=image_size,
                simplify=True,
            )
        )
    output_directory = Path("artifacts/litert") / onnx_path.stem
    subprocess.run(
        [
            "onnx2tf",
            "-i",
            str(onnx_path),
            "-o",
            str(output_directory),
        ],
        check=True,
    )
    suffix = "float16" if precision == "fp16" else "float32"
    exported = output_directory / f"{onnx_path.stem}_{suffix}.tflite"
    if not exported.is_file():
        raise FileNotFoundError(f"LiteRT converter did not produce {exported}")
    patched = make_litert_shapes_static(exported)
    print(f"Prepared {exported} for LiteRT GPU ({patched} shape signatures made static)")
    return exported


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Export a trained model for the edge runtime.")
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--format", choices=("onnx", "litert"), default="onnx")
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument(
        "--precision",
        choices=("fp32", "fp16", "int8"),
        default="fp32",
    )
    parser.add_argument("--data", type=Path, default=Path("configs/smart-bus.yaml"))
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if not args.model.is_file():
        raise FileNotFoundError(f"Model not found: {args.model}")
    if args.precision == "int8" and not args.data.is_file():
        raise FileNotFoundError(f"INT8 calibration dataset not found: {args.data}")

    if args.format == "litert":
        export_litert(args.model, args.imgsz, args.precision)
        return

    export_options: dict[str, object] = {
        "format": "onnx",
        "imgsz": args.imgsz,
    }
    if args.precision == "fp16":
        export_options["half"] = True
    elif args.precision == "int8":
        raise ValueError("ONNX INT8 export is not supported; quantize with deployment calibration tooling")

    exported = Path(YOLO(str(args.model)).export(**export_options))
    patched = prepare_onnx_for_nnapi(exported)
    print(f"Prepared {exported} for NNAPI ({patched} Split nodes patched)")


if __name__ == "__main__":
    main()
