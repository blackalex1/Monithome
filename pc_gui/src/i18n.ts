export type Language = 'ru' | 'en';

export const translations = {
  ru: {
    sidebar: {
      plugins: "Плагины",
      layout: "Расположение",
      import: "Импорт",
      serverOnline: "Сервер онлайн",
      serverOffline: "Сервер оффлайн",
      github: "GitHub Проект"
    },
    header: {
      managePlugins: "Управление Плагинами",
      tabletOrder: "Порядок на Планшете",
      importPlugin: "Импорт Плагинов",
      subtitle: "Конфигурация компонентов для мобильного приложения"
    },
    plugins: {
      setup: "Настроить",
      info: "Инфо",
      needed: "Нужен",
      unknownAuthor: "Неизвестен",
      authorResources: "Ресурсы автора",
      version: "Версия",
      author: "Автор",
      description: "Описание",
      dependencies: "Зависимости",
      close: "Понятно"
    },
    layout: {
      dragHint: "Перетаскивайте элементы, чтобы изменить порядок их отображения на планшете."
    },
    import: {
      title: "Импорт нового плагина",
      button: "Выбрать ZIP файл"
    },
    wizard: {
      title: "Настройка",
      loading: "Загрузка мастера настройки...",
      save: "Сохранить",
      cancel: "Отмена"
    },
    pairing: {
      title: "Новое подключение",
      description: "Устройство пытается подключиться к вашему ПК. Введите этот код на планшете:",
      code: "Код авторизации",
      cancel: "Отклонить",
      success: "Устройство успешно привязано!"
    }
  },
  en: {
    sidebar: {
      plugins: "Plugins",
      layout: "Layout",
      import: "Import",
      serverOnline: "Server Online",
      serverOffline: "Server Offline",
      github: "GitHub Project"
    },
    header: {
      managePlugins: "Plugin Management",
      tabletOrder: "Tablet Layout",
      importPlugin: "Import Plugins",
      subtitle: "Configuration for the mobile application"
    },
    plugins: {
      setup: "Setup",
      info: "Info",
      needed: "Needs",
      unknownAuthor: "Unknown",
      authorResources: "Author Resources",
      version: "Version",
      author: "Author",
      description: "Description",
      dependencies: "Dependencies",
      close: "Got it"
    },
    layout: {
      dragHint: "Drag and drop items to change their display order on the tablet."
    },
    import: {
      title: "Import new plugin",
      button: "Select ZIP file"
    },
    wizard: {
      title: "Settings",
      loading: "Loading setup wizard...",
      save: "Save",
      cancel: "Cancel"
    },
    pairing: {
      title: "New Connection",
      description: "A device is trying to connect to your PC. Enter this code on the tablet:",
      code: "Auth Code",
      cancel: "Reject",
      success: "Device paired successfully!"
    }
  }
};
