import { DEV_MOCK_ENABLED } from "./enabled"

export { DEV_MOCK_TOKEN, mockUser } from "./data"
export { installDevMock } from "./install"
export { DEV_MOCK_ENABLED }

export function isDevMockAvailable(): boolean {
  return DEV_MOCK_ENABLED
}
