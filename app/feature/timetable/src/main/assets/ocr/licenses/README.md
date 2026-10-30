# Bundled local OCR attribution

The host remains GPL-3.0-only, with its existing original-project credits retained. This directory adds the full upstream notices for the bundled OCR components; it does not replace the host license.

- Tesseract4Android 4.9.0 standard AAR, Apache-2.0, adaptech s.r.o. and contributors. Wrapper commit `15c534717b1cb58261b58d4e4c1200c7f81f668c`; `wrapper-LICENSE`.
- Tesseract 5.5.1, Apache-2.0; `tesseract-LICENSE`.
- Official tessdata_fast models `chi_sim.traineddata` and `eng.traineddata`, Apache-2.0. Model commit `87416418657359cb625c412a48b6e1d6d41c29bd`; `tessdata-LICENSE`.
- Leptonica 1.85.0, BSD-style notice; `leptonica-license`.
- libjpeg v9f, Independent JPEG Group license and acknowledgment; `libjpeg-README`. This software is based in part on the work of the Independent JPEG Group.
- libpng 1.6.48, PNG Reference Library license; `libpng-LICENSE`.

All six full original notice files, and this attribution, are also included in APK assets at `ocr/licenses/` for the application's About/licenses screen. No license text has been shortened. No OpenMP variant is used.

Upstream: https://github.com/adaptech-cz/Tesseract4Android/tree/15c534717b1cb58261b58d4e4c1200c7f81f668c and https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd .

Bundled model pins:

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| chi_sim.traineddata | 2469156 | a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730 |
| eng.traineddata | 4113088 | 7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2 |

Expected resolved standard AAR: 12848454 bytes; SHA-256 bce5d6413a1a5ae3d7240033fbbc851ba3217d0a08d9769400e17a077f42cb2a.
