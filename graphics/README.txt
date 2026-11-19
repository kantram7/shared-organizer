Графика

icons/ — рабочие SVG-исходники интерфейса.
android/drawable/ic_*.xml — соответствующие Android VectorDrawable.
tools/svg_to_vector.py — преобразование используемого подмножества SVG без растеризации.
Для обновления ресурсов: python graphics/tools/svg_to_vector.py
mockups/mockup-v1.html — утверждённый HTML-макет v1, историческая версия дизайна.

Маппинг и размеры иконок: android/app/src/main/java/team/kotiki/app/ui/icons/KotikiIcons.kt.
Все значки рисуются из векторных ресурсов с ContentScale.Fit; раздельного растяжения по осям нет.
cat-face.svg — одна мордочка 24x24; pair.svg — две такие же мордочки, 48x24.
private-lock.svg — выбранный замок для закрытых задач, событий и списков.
eye-off.svg — занятость со скрытыми подробностями.
Пояснения по нажатию и описания доступности задаются в AccessIcon.
Иконки не меняют правила доступа и хранения данных.

settings.svg — контурная шестерёнка; menu.svg — кнопка раскрываемого меню.
Планы только общие, их темы и пункты не маркируются значками доступа.

Иконки участников
cat-face.svg — Пользователь 1; cat-girl.svg — Пользователь 2, с бантом. pair.svg объединяет обе мордочки без уменьшения: 48×24, каждая 24×24.
cat-girl.svg содержит маску просвета вокруг банта. Она и парная композиция рисуются в Compose (ui/icons/CatGirlIcon.kt и KotikiIcons.kt) исходными векторными контурами; без растрового преобразования. SVG-конвертер намеренно пропускает эти два файла. Просвет выполняется через BlendMode.Clear в отдельном слое, поэтому работает на разных фонах и в тёмной теме.

Архив дизайна v1: mockups/winter-together-v1.html — сохранённый дизайн; themes/winter-together-v1/design.txt — статус, палитра и ограничения. Нативный вектор леса — android/drawable/winter_forest.xml. Актуальная реализация v2 встроена в APK 0.3.4; удалённый флаг пока выключен.

Зима вместе v2: themes/winter-together-v2 и mockups/winter-together-v2.html. Архив v1 сохранён отдельно.

Значок уведомлений 0.3.5: icons/notification-paw.svg — исходный вектор; android/drawable/ic_notification_paw.xml — одноцветный Android VectorDrawable. Используется для push, локальных напоминаний и превью уведомлений.
