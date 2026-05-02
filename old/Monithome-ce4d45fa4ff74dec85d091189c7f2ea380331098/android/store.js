import React, { useState, useEffect, useMemo } from 'react';

// Высокопроизводительный кольцевой буфер на типизированных массивах
class HistoryBuffer {
  constructor(size = 30) {
    this.size = size;
    this.buffer = new Float32Array(size);
    this.head = 0; // Указывает на следующее место для записи
    this.isFull = false;
  }

  push(value) {
    this.buffer[this.head] = value;
    this.head = (this.head + 1) % this.size;
    if (this.head === 0) this.isFull = true;
  }

  // Возвращает данные в правильном хронологическом порядке [oldest -> newest]
  getValues() {
    if (!this.isFull) {
      return Array.from(this.buffer.slice(0, this.head));
    }
    const result = new Float32Array(this.size);
    // Часть от head до конца - это старые данные
    const tailPart = this.buffer.slice(this.head);
    // Часть от начала до head - это новые данные
    const headPart = this.buffer.slice(0, this.head);
    result.set(tailPart, 0);
    result.set(headPart, tailPart.length);
    return Array.from(result);
  }
}

// GlobalStore - шина данных для "хирургических" обновлений
export class GlobalStore {
  static stats = {};
  static history = {}; // pId -> { key: HistoryBuffer }
  static listeners = {};
  static serverTime = 0;

  static subscribe(pluginIds, callback) {
    const ids = Array.isArray(pluginIds) ? pluginIds : [pluginIds];
    ids.forEach(id => {
      if (!this.listeners[id]) this.listeners[id] = new Set();
      this.listeners[id].add(callback);
    });
    return () => ids.forEach(id => this.listeners[id].delete(callback));
  }

  static update(pluginId, data) {
    this.stats[pluginId] = { ...this.stats[pluginId], ...data };
    this._updateHistory(pluginId, data);
    if (this.listeners[pluginId]) {
      this.listeners[pluginId].forEach(cb => cb(this.stats[pluginId], pluginId));
    }
  }

  static bulkUpdate(updates) {
    const now = Date.now() / 1000;
    Object.keys(updates).forEach(pId => {
      if (pId === "_server_time") return;
      this.stats[pId] = { ...this.stats[pId], ...updates[pId], _local_received_at: now };
      this._updateHistory(pId, updates[pId]);
      if (this.listeners[pId]) {
        this.listeners[pId].forEach(cb => cb(this.stats[pId], pId));
      }
    });
  }

  static _updateHistory(pId, data) {
    const numericKeys = Object.keys(data).filter(k => typeof data[k] === 'number');
    if (numericKeys.length === 0) return;

    if (!this.history[pId]) this.history[pId] = {};
    numericKeys.forEach(key => {
      if (!this.history[pId][key]) {
        this.history[pId][key] = new HistoryBuffer(30);
      }
      this.history[pId][key].push(data[key]);
    });
  }
}

// Хук для виджетов - подписка только на свой плагин(ы)
export const usePluginStats = (pluginIds) => {
  const isArray = Array.isArray(pluginIds);
  const ids = isArray ? pluginIds : [pluginIds];
  const idsKey = isArray ? pluginIds.join(',') : pluginIds;

  const [data, setData] = useState(() => {
    const initial = {};
    ids.forEach(id => { initial[id] = GlobalStore.stats[id] || {}; });
    return isArray ? initial : (initial[pluginIds] || {});
  });

  useEffect(() => {
    return GlobalStore.subscribe(pluginIds, (newData, pId) => {
      setData(prev => {
        if (isArray) {
          if (prev[pId] === newData) return prev;
          return { ...prev, [pId]: newData };
        }
        return newData;
      });
    });
  }, [idsKey]);
  
  return data;
};

// Хук для истории (графиков) с автоматической "распаковкой" буфера
export const useHistory = (pluginId) => {
  const [history, setHistory] = useState({});

  useEffect(() => {
    const update = () => {
      const pHistory = GlobalStore.history[pluginId];
      if (!pHistory) return;
      
      const unrolled = {};
      Object.keys(pHistory).forEach(key => {
        unrolled[key] = pHistory[key].getValues();
      });
      setHistory(unrolled);
    };

    update(); // Начальная загрузка
    return GlobalStore.subscribe(pluginId, update);
  }, [pluginId]);

  return history;
};
