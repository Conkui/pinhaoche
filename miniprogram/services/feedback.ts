// 意见反馈（RFC-006）。
import { request } from './request'

export function submitFeedback(content: string): Promise<void> {
  return request<void>('/feedback', { method: 'POST', data: { content } })
}
