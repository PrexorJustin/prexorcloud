import { describe, expect, it } from 'vitest';
import { isCidr, isEmail, isHttpUrl, isMongoOrRedis, isPort, portConflict } from './validators';

describe('isPort', () => {
  it.each([
    [1, true],
    [80, true],
    [65535, true],
    ['8080', true],
    [0, false],
    [65536, false],
    [-1, false],
    ['', false],
    ['abc', false],
    [null, false],
    [undefined, false],
  ])('isPort(%p) → %p', (input, expected) => {
    expect(isPort(input as never)).toBe(expected);
  });
});

describe('isMongoOrRedis', () => {
  it.each([
    ['mongodb://localhost', true],
    ['mongodb+srv://cluster.example', true],
    ['redis://localhost:6379', true],
    ['rediss://secure.example', true],
    ['  mongodb://leading-space  ', true],
    ['http://nope', false],
    ['', false],
    [null, false],
    [undefined, false],
  ])('isMongoOrRedis(%p) → %p', (input, expected) => {
    expect(isMongoOrRedis(input)).toBe(expected);
  });
});

describe('isHttpUrl', () => {
  it.each([
    ['http://example.com', true],
    ['https://example.com:8080/path', true],
    ['ftp://example.com', false],
    ['not-a-url', false],
    ['', false],
  ])('isHttpUrl(%p) → %p', (input, expected) => {
    expect(isHttpUrl(input)).toBe(expected);
  });
});

describe('isEmail', () => {
  it('treats empty as valid (caller decides required-ness)', () => {
    expect(isEmail('')).toBe(true);
    expect(isEmail(null)).toBe(true);
    expect(isEmail(undefined)).toBe(true);
  });
  it.each([
    ['user@example.com', true],
    ['user.name+tag@sub.example.co', true],
    ['not-an-email', false],
    ['missing@tld', false],
    ['@no-local.com', false],
  ])('isEmail(%p) → %p', (input, expected) => {
    expect(isEmail(input)).toBe(expected);
  });
});

describe('isCidr', () => {
  it.each([
    ['10.0.0.0/8', true],
    ['192.168.1.0/24', true],
    ['10.0.0.1', true], // bare IPv4 also valid
    ['::/0', true],
    ['fe80::/64', true],
    ['not-a-cidr', false],
  ])('isCidr(%p) → %p', (input, expected) => {
    expect(isCidr(input)).toBe(expected);
  });
});

describe('portConflict', () => {
  it('returns true when http and grpc collide', () => {
    expect(portConflict({ httpPort: 8080, grpcPort: 8080 })).toBe(true);
  });
  it('returns false when they differ', () => {
    expect(portConflict({ httpPort: 8080, grpcPort: 9090 })).toBe(false);
  });
  it('returns false when either is zero (treats unset as not-yet-conflicting)', () => {
    expect(portConflict({ httpPort: 0, grpcPort: 9090 })).toBe(false);
    expect(portConflict({ httpPort: 8080, grpcPort: 0 })).toBe(false);
  });
});
