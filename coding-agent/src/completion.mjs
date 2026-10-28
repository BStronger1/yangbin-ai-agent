// A successful HTTP stream or tool step does not establish a completed agent turn.
export function assertCompletedAssistant(message) {
  if (!message) throw new Error('模型未返回最终回复，本轮未完成；请检查已有工具结果后再继续。');
  if (message.stopReason === 'length') throw new Error('模型输出达到上限而被截断，本轮未完成。已有文件改动会保留，请检查后继续；不会自动重试。');
  if (['error', 'aborted'].includes(message.stopReason)) throw new Error(message.errorMessage || '模型响应失败，本轮未完成。');
  if (message.stopReason !== 'stop' || message.content?.some(part => part.type === 'toolCall')) throw new Error('模型工具流程未完整结束，本轮未完成。');
  if (!message.content?.some(part => part.type === 'text' && typeof part.text === 'string' && part.text.trim())) throw new Error('模型未生成可见的最终回复，本轮未完成；请检查已有工具结果后再继续。');
}
