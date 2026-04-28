# 🚀 Monithome

<p align="center">
  <img src="https://img.shields.io/badge/Monithome-v1.1.0-blue?style=for-the-badge&logo=react&logoColor=white" alt="Version">
  <img src="https://img.shields.io/badge/Python-3.8+-3776AB?style=for-the-badge&logo=python&logoColor=white" alt="Python">
  <img src="https://img.shields.io/badge/Platform-Windows-0078D6?style=for-the-badge&logo=windows&logoColor=white" alt="Platform">
  <img src="https://img.shields.io/badge/License-MIT-green?style=for-the-badge" alt="License">
</p>

---

**Monithome** — это современная экосистема для глубокого мониторинга вашего ПК и управления умным домом. Проект объединяет в себе мощный Python-агент для сбора данных и стильный кроссплатформенный дашборд на React / React Native.

> [!TIP]
> Идеально подходит для использования на старом планшете в качестве внешнего дисплея состояния системы или пульта управления умным домом.

---

## 📸 Скриншоты

<p align="center">
  <img src="./screenshots/pc.png" width="90%" alt="Manager UI">
</p>

<p align="center">
  <img src="./screenshots/main.png" width="90%" alt="Mobile UI">
</p>

---

## 🌟 Основные возможности

### 💻 Продвинутый мониторинг ПК
*   **Real-time графики**: Отслеживание CPU, GPU, RAM и температур с минимальной задержкой.
*   **Низкоуровневый доступ**: Интеграция с **LibreHardwareMonitor** и **MSI Afterburner** для получения самых точных данных.
*   **Дисковая подсистема**: Мониторинг свободного места на всех накопителях с поддержкой выбора конкретных дисков.
*   **Power Management**: Дистанционное управление питанием (сон, выключение, перезагрузка, блокировка) с подтверждением действий.

### 🏠 Умный дом и Медиацентр
*   **Яндекс Станция**: Управляйте музыкой и отправляйте текстовые команды Алисе прямо с планшета.
*   **Unified Media**: Единый центр управления громкостью и плеерами ПК прямо с планшета.
*   **Плагинная система**: Легко добавляйте новый функционал или отключайте ненужные модули через встроенный менеджер.

---

## 🛠 Технологический стек

```mermaid
graph TD
    A[Monithome Manager / Web] -->|Socket.IO| B(Python Agent)
    C[Monithome App / Android] -->|Socket.IO| B
    B --> D[Plugins]
    D --> E[System Stats]
    D --> F[PC Media]
    D --> G[PC System]
    D --> H[Yandex Station]
    E --> I[LibreHardwareMonitor]
```

*   **Backend**: Python, Flask, Socket.IO, Psutil, WMI.
*   **Web Manager**: Vite, React, TypeScript, Framer Motion, Lucide.
*   **Mobile Client**: React Native, Expo, Socket.IO Client.

---

## 🚀 Быстрый старт

### 1. Сервер (ПК)
1. Установите Python 3.8+.
2. Перейдите в папку `pc/`:
   ```bash
   python -m venv venv
   venv\Scripts\activate
   pip install -r requirements.txt
   python pc_agent.py
   ```
*Сервер автоматически запросит права администратора для доступа к датчикам.*

### 2. Менеджер (Web)
1. Перейдите в `pc_gui/`:
   ```bash
   npm install
   npm run build
   ```
*Собранный интерфейс будет доступен по адресу `http://localhost:5000` при запущенном агенте.*

### 3. Планшет / Смартфон (Android)
1. Установите приложение **Expo Go** на ваше устройство.
2. Перейдите в `android/`:
   ```bash
   npm install
   npx expo start --clear
   ```
3. Введите локальный IP вашего ПК.
---

<p align="center">
  <sub>Made with ❤️ for better hardware control</sub>
</p>
