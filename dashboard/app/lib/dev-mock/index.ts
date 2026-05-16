export { DEV_MOCK_TOKEN, mockUser } from "./data"
export { installDevMock } from "./install"

export function isDevMockAvailable(): boolean {
  return import.meta.env.DEV
}
