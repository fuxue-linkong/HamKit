/**
 * 路由内核 —— 极小的模式匹配路由器，配合零依赖 HTTP 服务使用。
 *
 * 支持的模式：`/api/versions/:id`、`/static/*`。
 * 通道（ctx.state.channel）用于把前台/后台/API 分开限流与缓存策略。
 */

export class RouteError extends Error {
  constructor(status, message, code = null, details = null) {
    super(message);
    this.name = 'RouteError';
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

/**
 * 把模式编译成正则。
 * 参数占位符 `:name` 可以嵌在片段中间（如 `/update_:channel.json`），
 * 因此不能只按 '/' 切分后整段匹配：先按占位符切开，字面量转义、占位符换成捕获组。
 */
function compile(pattern) {
  if (pattern.endsWith('/*')) {
    const base = pattern.slice(0, -2);
    return { regex: new RegExp(`^${escapeRegex(base)}/(?<wild>.*)$`), names: [], wildcard: true };
  }
  const names = [];
  const source = pattern
    .split('/')
    .map((segment) => {
      if (segment === '*') {
        names.push('wild');
        return '(.*)';
      }
      const parts = [];
      const token = /:([A-Za-z_][A-Za-z0-9_]*)/g;
      let last = 0;
      let m;
      while ((m = token.exec(segment)) !== null) {
        parts.push(escapeRegex(segment.slice(last, m.index)));
        names.push(m[1]);
        parts.push('([^/]+)');
        last = m.index + m[0].length;
      }
      parts.push(escapeRegex(segment.slice(last)));
      return parts.join('');
    })
    .join('/');
  return { regex: new RegExp(`^${source}$`), names, wildcard: false };
}

function escapeRegex(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export function createRouter() {
  const routes = [];

  function add(method, pattern, handler, { channel = 'api', name = null } = {}) {
    const { regex, names, wildcard } = compile(pattern);
    routes.push({ method: method.toUpperCase(), pattern, regex, names, wildcard, handler, channel, name });
    return api;
  }

  const api = {
    get: (p, h, o) => add('GET', p, h, o),
    post: (p, h, o) => add('POST', p, h, o),
    put: (p, h, o) => add('PUT', p, h, o),
    patch: (p, h, o) => add('PATCH', p, h, o),
    delete: (p, h, o) => add('DELETE', p, h, o),
    add,
    get routes() {
      return routes;
    },
    /** 匹配一次请求；返回 { route, params } 或 null。OPTIONS/HEAD 交给调用方兜底。 */
    match(method, pathname) {
      const upper = method.toUpperCase();
      let pathMatchedButMethodDiffers = false;
      for (const route of routes) {
        const m = route.regex.exec(pathname);
        if (!m) continue;
        if (route.method !== upper) {
          pathMatchedButMethodDiffers = true;
          continue;
        }
        const params = {};
        if (route.wildcard) {
          params.wild = m.groups?.wild ?? '';
        } else {
          route.names.forEach((n, i) => {
            params[n] = decodeURIComponentSafe(m[i + 1]);
          });
        }
        return { route, params };
      }
      return pathMatchedButMethodDiffers ? { methodNotAllowed: true } : null;
    },
  };

  return api;
}

function decodeURIComponentSafe(value) {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}
