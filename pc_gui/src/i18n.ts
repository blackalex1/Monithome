export type Language = 'ru' | 'en';

export interface TranslationSchema {
  sidebar: {
    plugins: string;
    layout: string;
    import: string;
    serverOnline: string;
    serverOffline: string;
    github: string;
  };
  header: {
    managePlugins: string;
    tabletOrder: string;
    importPlugin: string;
    subtitle: string;
  };
  plugins: {
    setup: string;
    info: string;
    needed: string;
    unknownAuthor: string;
    authorResources: string;
    version: string;
    author: string;
    description: string;
    dependencies: string;
    close: string;
  };
  layout: {
    dragHint: string;
  };
  import: {
    title: string;
    button: string;
  };
  wizard: {
    title: string;
    loading: string;
    save: string;
    cancel: string;
  };
  pairing: {
    title: string;
    description: string;
    code: string;
    cancel: string;
    success: string;
  };
}

export const defaultTranslations: TranslationSchema = {
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
};
