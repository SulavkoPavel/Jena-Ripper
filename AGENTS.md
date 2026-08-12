# Jena Ripper — стабильная рабочая версия

## CIM App — только чтение

- Репозиторий `C:\Users\Павел\IdeaProjects\cim-app` разрешено только просматривать для анализа совместимости.
- Запрещено создавать, изменять, удалять, форматировать или коммитить файлы в `cim-app`.
- Любую интеграцию реализовывать только на стороне Jena Ripper через уже существующий API CIM App или отдельные подключения Jena Ripper.

Приложение находится в рабочем состоянии. При любой задаче изменять только то, о чём попросил пользователь. Запрещён побочный рефакторинг.

## Перед каждым изменением

1. Проверить `git status`.
2. Определить минимальный список файлов, которые действительно нужно изменить.
3. Не изменять unrelated files.
4. Не форматировать весь проект.
5. Не переименовывать классы без функциональной необходимости.
6. Не обновлять dependencies без запроса.
7. Не менять работающий API без необходимости.

## Запрещено без отдельной задачи

- Spring upgrade, Jena upgrade, Redis client migration.
- React/state-management architecture rewrite.
- Graph library replacement, global CSS redesign.
- REST API/DTO redesign, package restructure, mass rename.

## Regression-critical функции

Обязательно сохранять: Graph Explorer, SPARQL, Owner Rules, Redis Rules, Connections, Profiles, Search, Node Inspector, Graph expand/collapse, Prefixes.

## Границы изменений

- При локальном UI-изменении backend не менять, если текущий API уже достаточен.
- При backend-изменении одного feature не менять endpoints других features.
- Локальное замечание пользователя разрешает только локальное исправление с smallest possible diff.

## Проверка

После каждого изменения выполнить backend build, frontend build и smoke-check затронутой функциональности. Перед завершением просмотреть `git diff` и убедиться, что unrelated изменений нет.

В итоговом отчёте явно указать: что изменено, что не изменялось, и результат regression check.

## Java style

- Do not use `var`.
- Use explicit local variable types.
