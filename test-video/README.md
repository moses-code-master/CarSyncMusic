# Original video test fixtures

CarSync-Landscape.mp4, CarSync-Landscape.mkv and CarSync-Portrait.mp4 contain original synthetic FFmpeg testsrc2 patterns and sine waves, created for CarSync validation. Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/

No third-party recordings, music or footage. The generator/tool is not distributed in the APK. H.264 baseline, YUV420p, 15 fps, AAC mono, 45 seconds; landscape 320×180 and portrait 180×320. MKV is a remux of the landscape fixture, deliberately having a different file hash. Instrumentation uses copies of these fixtures in androidTest/assets; the user APK does not include them.

Generation recipe (FFmpeg installed separately):

    ffmpeg -f lavfi -i testsrc2=size=320x180:rate=15 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 45 -c:v libx264 -preset ultrafast -profile:v baseline -pix_fmt yuv420p -b:v 128k -c:a aac -b:a 32k -ac 1 -movflags +faststart CarSync-Landscape.mp4
    ffmpeg -i CarSync-Landscape.mp4 -c copy CarSync-Landscape.mkv

For portrait, use size=180x320 and frequency=660 with the same codec/duration settings.
