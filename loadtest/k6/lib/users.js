// 压测账号装载。
//
// 数据来源：loadtest/scripts/gen_k6_users.py 由 data/users.csv 生成的静态模块。
// （k6 v2 移除了 open()，用生成模块的方式最稳。）
//
// 用 SharedArray 包装：数据只在初始化阶段构建一次，各 VU 共享只读副本，
// 避免 1000 个 VU 各存一份 1000 条账号数据。
import { SharedArray } from 'k6/data';
import { users as generated } from '../data/users.js';

export const users = new SharedArray('users', function () {
  return generated;
});

/** 按 VU 序号取固定账号：同一 VU 始终用同一用户，与 JMeter CSV 行为对齐。 */
export function userForVU(vu) {
  return users[(vu - 1) % users.length];
}
