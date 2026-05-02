export interface PluginInfo {
  id: string;
  name: string;
  active: boolean;
  config?: any;
  version?: string;
  author?: string;
  author_name?: string;
  description?: string;
  name_en?: string;
  description_en?: string;
  dependencies?: string[];
}

export interface MasterConfig {
  active_plugins: string[];
  plugin_order?: string[];
  language?: string;
  plugin_settings: any;
}
