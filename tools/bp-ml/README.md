# tools/bp-ml: offline tools for the blood-pressure model

Everything the app ships is built or checked here. See `docs/algorithms/BP_ALGORITHM.md` for the algorithm.

| Script | What it does |
|---|---|
| `export_papagei_onnx.py` | Exports the PaPaGei-S PPG encoder (Nokia Bell Labs, BSD-3-Clause, [paper](https://arxiv.org/abs/2410.20542), [weights](https://zenodo.org/records/13983110)) to ONNX, checks it against PyTorch, quantizes to int8 → `phone/src/main/assets/papagei_s_int8.onnx` (5.7 MB, cosine 0.9997 to fp32). |
| `evaluate_embedders.py` | On a BP dataset exported from the phone, scores "watch as is", the app's pulse-shape embedding and PaPaGei by leave-one-out, the same way the app's gate does. |
| `ppg_prep.py` | Preprocessing shared by both (mirrors `PapageiInput` in `shared`). |

The classical algorithm itself is replayed in Kotlin, on the same exported file:

```bash
BP_DATASET=/path/heartline-bp.json ./gradlew :shared:test --tests '*BpDatasetReport*' -i
```

It prints mean difference ± SD, MAE, % within 10 mmHg, the proportional-bias slope (how much
readings are pulled towards the calibration) and how many readings were flagged beyond the
calibration, with and without cuff checks added to the calibration.

## Getting data

Phone → Blood pressure → Share → **BP data (JSON)**: the calibration (with its raw PPG) and every
reading that was compared with a cuff (raw PPG, cuff and watch values). It stays on the device
unless you share it.

## Setup

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
git clone https://github.com/Nokia-Bell-Labs/papagei-foundation-model papagei
curl -L -o papagei_s.pt "https://zenodo.org/records/13983110/files/papagei_s.pt?download=1"
.venv/bin/python export_papagei_onnx.py --repo papagei --weights papagei_s.pt --out ../../phone/src/main/assets/papagei_s_int8.onnx
.venv/bin/python evaluate_embedders.py heartline-bp.json --onnx ../../phone/src/main/assets/papagei_s_int8.onnx
```

## Population training (not done yet, and why)

PulseDB / VitalDB / MIMIC-III hold finger PPG from surgery and ICU patients with arterial-line
pressure. Models trained on them lose much of their accuracy on other people and devices
(calibration-free MAE ≈ 14/8.5 mmHg), and wrist green PPG differs from finger PPG. So the app
does not ship a population BP regressor: the pretrained encoder is only a feature extractor, and
the regression is learned per user from their own cuff checks, behind a leave-one-out gate. A
population head is worth training once enough exported wrist data exists to validate it on
people it wasn't trained on.
