/**
 * 兼容文件：App 与旧官网依赖的静态 JSON，全部由版本数据派生。
 *
 *   update.json         —— App 内更新检查（versionCode / versionName / downloadUrl / changelog）
 *   update_<渠道>.json   —— 分渠道更新检查
 *   version.json        —— 官网版本徽标（tag / prerelease / updated_at）
 *
 * 单一事实来源是 data/versions.json；这些文件是它的投影，
 * 因此后台改版本后 App 拉取到的内容会自动同步，不会出现两处漂移。
 */

import fs from 'node:fs/promises';
import path from 'node:path';

export const COMPAT_FILES = ['update.json', 'version.json'];

/** 取派生文件（getter 语义，总是最新）。 */
export async function toVersionFiles(versions, { now = () => Date.now() } = {}) {
  const files = await versions.compatFiles();
  void now;
  return files;
}

/**
 * 把派生文件写回磁盘（可选）。
 * 用途：给不经过 Node 服务的 CDN / 对象存储 / GitHub Pages 提供静态文件。
 * 返回写出的文件绝对路径列表。
 */
export async function materializeCompatFiles(versions, { dir, logger = console, now = () => Date.now() } = {}) {
  const files = await toVersionFiles(versions, { now });
  const written = [];
  await fs.mkdir(dir, { recursive: true });
  for (const [name, value] of Object.entries(files)) {
    if (name === 'channels') continue;
    const target = path.join(dir, name);
    const body = `${JSON.stringify(value, null, 2)}\n`;
    await fs.writeFile(target, body, 'utf8');
    written.push(target);
  }
  if (files.channels) {
    for (const [channel, value] of Object.entries(files.channels)) {
      const target = path.join(dir, `update_${channel}.json`);
      await fs.writeFile(target, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
      written.push(target);
    }
  }
  logger.debug?.(`[compat] 已写出 ${written.length} 个兼容文件到 ${dir}`);
  return written;
}
