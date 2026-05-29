/**
 * 读取 Spring WebFlux text/event-stream（SSE）响应，按 data 行回调片段。
 */
export async function readSseDataLines(
  response: Response,
  onData: (chunk: string) => void
): Promise<void> {
  if (!response.body) {
    throw new Error("响应无 body");
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  const flushEvents = (text: string): string => {
    const events = text.split("\n\n");
    const rest = events.pop() ?? "";
    for (const block of events) {
      for (const line of block.split("\n")) {
        if (line.startsWith("data:")) {
          onData(line.slice(5).trimStart());
        }
      }
    }
    return rest;
  };

  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      buffer = flushEvents(buffer);
    }
    if (buffer.trim()) {
      flushEvents(buffer + "\n\n");
    }
  } finally {
    reader.releaseLock();
  }
}
