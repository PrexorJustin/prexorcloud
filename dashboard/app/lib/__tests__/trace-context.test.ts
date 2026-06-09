import { describe, it, expect, beforeEach } from "vitest"

import { lastTraceId, recordTraceId, buildTraceUrl } from "../trace-context"

describe("trace-context", () => {
  beforeEach(() => {
    lastTraceId.value = ""
  })

  it("records a non-empty trace id", () => {
    recordTraceId("abc123")
    expect(lastTraceId.value).toBe("abc123")
  })

  it("ignores empty/missing values so a header-less response doesn't clobber the last id", () => {
    recordTraceId("abc123")
    recordTraceId(null)
    recordTraceId(undefined)
    recordTraceId("")
    expect(lastTraceId.value).toBe("abc123")
  })

  it("builds a deep link by substituting {traceId}", () => {
    expect(buildTraceUrl("http://jaeger/trace/{traceId}", "deadbeef")).toBe("http://jaeger/trace/deadbeef")
  })

  it("returns null without a template or trace id", () => {
    expect(buildTraceUrl("", "deadbeef")).toBeNull()
    expect(buildTraceUrl(undefined, "deadbeef")).toBeNull()
    expect(buildTraceUrl("http://jaeger/trace/{traceId}", "")).toBeNull()
  })
})
