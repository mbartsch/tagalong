# Third-party notices

This project is licensed under the Apache License 2.0 (see `LICENSE` and `NOTICE`). It includes or depends on
the following. The birdcam files below stay under their own MIT license, which is compatible with Apache-2.0.

## birdcam — authentication key material (MIT)

`app/src/main/java/cl/bartsch/tagalong/LssConstants.kt` contains the key tables from
<https://github.com/attilaolah/birdcam> (`nikon/ls_sec/data.h`). That license requires the notice
below to stay with these files and any copy of them.

```
MIT License

Copyright (c) 2022 Attila Oláh

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Apache License 2.0 libraries (Android app)

Used as unmodified dependencies, license text at <https://www.apache.org/licenses/LICENSE-2.0>.
A distributed APK must carry that license text and any NOTICE files of these libraries.

- AndroidX (activity-compose, core, and related) and Jetpack Compose (ui, material3, tooling-preview)
- kotlinx.coroutines
- Gradle wrapper

## Not included

No Nikon software is included or licensed here. SnapBridge, NX MobileAir and Nikon's native libraries
are Nikon's and must not be redistributed. "Nikon", "SnapBridge" and "D780" are trademarks of
Nikon Corporation; this project is unofficial and not affiliated with Nikon.
