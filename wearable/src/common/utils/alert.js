/**
 * 卫星过境提醒的协议常量与纯函数工具。
 *
 * 这里刻意不引入任何 Vela API（interconnect / router / vibrator），
 * 保证这些逻辑能在 Node 下直接单测，也方便手机端复用同一套字段名。
 */

/** 协议版本，手机端与手环端需一致 */
export const PROTOCOL_VERSION = 1

/** 手机 → 手环：卫星即将过境 */
export const MSG_PASS = 'pass'

/** 手环 → 手机：握手，请求补发最近一条未过期提醒 */
export const MSG_HELLO = 'hello'

/** 手环 → 手机：提醒已展示 / 已被用户上滑关闭 */
export const MSG_ACK = 'ack'

/** 手环展示后多久内仍算有效（AOS 过后保留 2 分钟，避免刚过境就闪退） */
export const EXPIRE_AFTER_AOS_MS = 2 * 60 * 1000

/**
 * 宽松解析一条来自手机的消息。
 *
 * 为什么不用一次 JSON.parse：手机端可能把整个对象序列化后放进 `data`，
 * 也可能先把对象转成字符串再被框架序列化一次，手环拿到的可能是双层 JSON。
 * 逐层解到对象为止，解析失败返回 null，调用方按"忽略该消息"处理。
 */
export function parseMessage(raw) {
  let value = raw
  for (let i = 0; i < 3; i++) {
    if (typeof value !== 'string') break
    try {
      value = JSON.parse(value)
    } catch (e) {
      return null
    }
  }
  if (!value || typeof value !== 'object') return null
  return value
}

/** 字段是否齐全且类型正确（缺字段的脏数据直接丢弃，避免 NaN 显示） */
export function isValidAlert(msg) {
  return !!(
    msg &&
    msg.t === MSG_PASS &&
    typeof msg.id === 'string' &&
    typeof msg.sat === 'string' &&
    typeof msg.aos === 'number' &&
    isFinite(msg.aos)
  )
}

/**
 * 计算手环时钟与手机时钟的偏移量（毫秒）。
 *
 * 手环与手机的时钟可能差几十秒，直接用本地时间倒计时会出现 "AOS 00:00" 提前/延后。
 * 用消息自带的发送时刻 now 反推偏移，后续 tick 统一用 `本地时间 + offset`。
 */
export function computeClockOffset(msg, localNow) {
  if (!msg || typeof msg.now !== 'number' || !isFinite(msg.now)) return 0
  return msg.now - localNow
}

/** 距离 AOS 还剩多少毫秒（可能为负：已经过境） */
export function remainingMs(msg, localNow, offset) {
  const correctedNow = localNow + (offset || 0)
  return msg.aos - correctedNow
}

/** 是否已经过期到不值得再弹（AOS + 2 分钟之后） */
export function isExpired(msg, localNow, offset) {
  return remainingMs(msg, localNow, offset) < -EXPIRE_AFTER_AOS_MS
}

/** 秒数 → "MM:SS"；负数按 0 处理，超过 99 分钟按 99:59 截断 */
export function formatCountdown(totalSeconds) {
  let sec = Math.floor(totalSeconds)
  if (!isFinite(sec) || sec < 0) sec = 0
  if (sec > 99 * 60 + 59) sec = 99 * 60 + 59
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return (m < 10 ? '0' + m : '' + m) + ':' + (s < 10 ? '0' + s : '' + s)
}

/** 构造握手消息对象 */
export function makeHello() {
  return { t: MSG_HELLO, v: PROTOCOL_VERSION }
}

/** 构造 ack 消息对象；action 取 "shown" 或 "dismiss" */
export function makeAck(id, action, now) {
  return { t: MSG_ACK, v: PROTOCOL_VERSION, id: id, act: action, at: now }
}

/**
 * 造一条测试用提醒（调试页"测试提醒"按钮使用）。
 * @param {number} secondsAhead 多少秒后 AOS
 */
export function makeTestAlert(secondsAhead, now) {
  const aos = now + secondsAhead * 1000
  return {
    t: MSG_PASS,
    v: PROTOCOL_VERSION,
    id: 'test-' + aos,
    sat: 'ISS',
    norad: 25544,
    aos: aos,
    los: aos + 10 * 60 * 1000,
    el: 42,
    az: 128,
    modes: ['FM'],
    now: now
  }
}
