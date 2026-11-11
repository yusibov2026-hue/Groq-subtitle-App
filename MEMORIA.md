# Memoria del proyecto

- 2026-11-11: Implementación nativa de Groq Subtitle Generator en Android (Kotlin). Integración con Groq Whisper Large V3 / Turbo y Llama 3.3 / Qwen для перевода, извлечение аудиодорожки через MediaExtractor/MediaMuxer, встроенный плеер видео с наложением субтитров в реальном времени, редактор сегментов и экспорт SRT/VTT.
- 2026-11-11: Исправлена ошибка broken pipe: добавлен декодер MediaCodec в 16-бит PCM WAV для любых форматов видео, внедрен streaming режим передачи (setFixedLengthStreamingMode), корректные MIME-типы и обработка ранних кодов ответов сервера Groq.
- 2026-11-11: APK пересобран после фикса broken pipe (app-debug.apk, 863 KB, подписан).
- 2026-11-11: Добавлена нарезка длинного аудио на части <18 МБ (AAC remux, WAV split, decode в WAV), транскрибация по частям с восстановлением таймкодов и пакетный перевод по 40 сегментов. APK 871 KB собран.
- 2026-11-11: Исправлена ошибка Groq 400 «Content-Type isn't multipart/form-data»: boundary содержал '=', что ломало разбор заголовка. Теперь boundary — валидный токен без '='.
- 2026-11-11: Acento azul (#2EA8FF) en tema/botones, añadido botón de pantalla completa en el reproductor (inmersivo, salir con «Свернуть» o atrás). APK 873 KB firmado.
- 2026-11-11: Proyecto subido a GitHub: https://github.com/yusibov2026-hue/Groq-subtitle-App (público, rama main).
- 2026-11-11: APK firmado publicado en la raíz del repositorio: Groq-Subtitle-App.apk (873 KB), excepción en .gitignore.
