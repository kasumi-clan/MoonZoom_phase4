#!/usr/bin/env python3
"""
Chuyển 3 model (.pth/.pt) sang ONNX, rồi KIỂM TRA bằng ảnh test (so PyTorch với ONNX Runtime) trước khi
đưa vào APK. Chạy trên GitHub Actions (workflow "Convert models").

  python tools/convert_models.py <thư mục chứa trọng số> <thư mục xuất>

Xuất: retinexformer_256.onnx, swinir_x4_64.onnx (nếu thành công), report.md, ảnh before/after.
Model nào lỗi thì ghi rõ lý do vào report.md và KHÔNG tạo file ONNX giả.
"""
import glob
import importlib.util
import os
import subprocess
import sys
import traceback

import numpy as np
from PIL import Image

SRC = sys.argv[1] if len(sys.argv) > 1 else "models_src"
OUT = sys.argv[2] if len(sys.argv) > 2 else "out"
TEST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "test.jpg")
os.makedirs(OUT, exist_ok=True)
REPORT = []


def log(s):
    print(s, flush=True)
    REPORT.append(s)


def find_weight(*keys):
    for f in sorted(glob.glob(os.path.join(SRC, "*"))):
        name = os.path.basename(f).lower()
        if name.endswith((".onnx", ".md", ".txt", ".png")):
            continue
        if all(k.lower() in name for k in keys):
            return f
    return None


def clone(url, dest):
    if not os.path.isdir(dest):
        subprocess.run(["git", "clone", "--depth", "1", url, dest], check=True)


def load_module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_tensor(size, darken=1.0):
    im = Image.open(TEST).convert("RGB").resize((size, size), Image.BICUBIC)
    a = np.asarray(im).astype(np.float32) / 255.0 * darken
    return a.transpose(2, 0, 1)[None].copy()


def save_img(t, path):
    a = np.clip(t[0].transpose(1, 2, 0), 0, 1)
    Image.fromarray((a * 255 + 0.5).astype(np.uint8)).save(path)


def export_and_check(name, model, size, scale, out_file, darken=1.0, tol=2e-2):
    import onnxruntime as ort
    import torch

    model.eval()
    x = torch.from_numpy(test_tensor(size, darken))
    with torch.no_grad():
        ref = model(x).numpy()
    path = os.path.join(OUT, out_file)
    torch.onnx.export(
        model, x, path, input_names=["input"], output_names=["output"],
        opset_version=17, do_constant_folding=True,
    )
    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    got = sess.run(None, {sess.get_inputs()[0].name: x.numpy()})[0]
    exp_shape = (1, 3, size * scale, size * scale)
    diff = float(np.abs(ref - got).max())
    mean_in, mean_out = float(x.numpy().mean()), float(got.mean())
    ok = got.shape == exp_shape and np.isfinite(got).all() and diff < tol
    save_img(x.numpy(), os.path.join(OUT, f"{name}_before.png"))
    save_img(got, os.path.join(OUT, f"{name}_after.png"))
    log(f"- {name}: {'OK' if ok else 'LỆCH/LỖI'} | shape {got.shape} (mong đợi {exp_shape}) | "
        f"sai khác tối đa PyTorch-ONNX = {diff:.5f} | độ sáng TB {mean_in:.3f} -> {mean_out:.3f} | "
        f"{os.path.getsize(path) / 1e6:.1f} MB")
    if not ok:
        os.remove(path)
        log(f"  => {name}: kết quả ONNX không khớp PyTorch, ĐÃ XOÁ file ONNX (không đưa vào APK).")
    return ok


def do_retinexformer():
    log("\n## Retinexformer (LOL_v2_real)")
    w = find_weight("lol")
    if not w:
        return log("- Không tìm thấy file trọng số 'LOL...' trong thư mục models.")
    try:
        import torch
        clone("https://github.com/caiyuanhao1998/Retinexformer", "repos/retinex")
        arch = glob.glob("repos/retinex/**/RetinexFormer_arch.py", recursive=True)[0]
        mod = load_module(arch, "retinex_arch")
        net = mod.RetinexFormer(in_channels=3, out_channels=3, n_feat=40, stage=1, num_blocks=[1, 2, 2])
        ck = torch.load(w, map_location="cpu")
        sd = ck.get("params", ck)
        net.load_state_dict(sd, strict=True)
        export_and_check("retinexformer", net, 256, 1, "retinexformer_256.onnx", darken=0.25)
    except Exception:
        log("- LỖI chuyển đổi Retinexformer:\n```\n" + traceback.format_exc() + "```")


def do_swinir():
    log("\n## SwinIR-M x4 (BSRGAN real-world GAN)")
    w = find_weight("swinir")
    if not w:
        return log("- Không tìm thấy file trọng số 'SwinIR...' trong thư mục models.")
    try:
        import torch
        clone("https://github.com/JingyunLiang/SwinIR", "repos/swinir")
        mod = load_module("repos/swinir/models/network_swinir.py", "swinir_arch")
        net = mod.SwinIR(
            upscale=4, in_chans=3, img_size=64, window_size=8, img_range=1.0,
            depths=[6] * 6, embed_dim=180, num_heads=[6] * 6, mlp_ratio=2,
            upsampler="nearest+conv", resi_connection="1conv",
        )
        ck = torch.load(w, map_location="cpu")
        sd = ck.get("params_ema", ck.get("params", ck))
        net.load_state_dict(sd, strict=True)
        export_and_check("swinir", net, 64, 4, "swinir_x4_64.onnx", tol=5e-2)
    except Exception:
        log("- LỖI chuyển đổi SwinIR:\n```\n" + traceback.format_exc() + "```")


def do_deeplpf():
    log("\n## DeepLPF (deep_lpf_best.pt)")
    w = find_weight("deep_lpf") or find_weight("deeplpf")
    if not w:
        return log("- Không tìm thấy file trọng số DeepLPF.")
    try:
        import torch
        sd = torch.load(w, map_location="cpu")
        keys = list(sd.keys())
        log(f"- File chứa {len(keys)} tensor: U-Net backbone (enc1/enc2/bottleneck/dec2/dec1/out_conv), "
            f"polynomial_filter {tuple(sd['polynomial_filter.params'].shape)}, "
            f"graduated_filter {tuple(sd['graduated_filter.params'].shape)}, "
            f"elliptical_filter {tuple(sd['elliptical_filter.params'].shape)}.")
        log("- KHÔNG chuyển đổi được: đây không phải DeepLPF gốc (bản gốc dùng backbone ResNet và dự đoán tham số "
            "theo ảnh); file chỉ là state_dict, thiếu mã định nghĩa forward (cách ghép U-Net với các bộ lọc). "
            "Cần file .py định nghĩa mô hình (class + forward) để chuyển ONNX đúng. Không tạo ONNX đoán mò.")
    except Exception:
        log("- LỖI đọc DeepLPF:\n```\n" + traceback.format_exc() + "```")


if __name__ == "__main__":
    log("# Báo cáo chuyển đổi model\n")
    log(f"Trọng số: {sorted(os.listdir(SRC)) if os.path.isdir(SRC) else 'không có thư mục ' + SRC}")
    do_retinexformer()
    do_swinir()
    do_deeplpf()
    with open(os.path.join(OUT, "report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(REPORT))
    print("\nXong. Xem", os.path.join(OUT, "report.md"))
