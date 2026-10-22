import { test } from 'node:test';
import assert from 'node:assert/strict';
import { LOCATION_TTL_MS, recentLocation } from '../src/location-policy.mjs';

test('short mobile GPS dropouts keep shared car visible without fabricating fixes', () => {
  const gps = {lat:-1.28, lon:36.8, accuracy:15, at:1000};
  assert.equal(LOCATION_TTL_MS, 30000);
  assert.deepEqual(recentLocation(gps, 1000), gps);
  assert.deepEqual(recentLocation(gps, 10999), gps);
  assert.deepEqual(recentLocation(gps, 30999), gps);
  assert.equal(recentLocation(gps, 31000), null);
});

test('opt-out and invalid GPS timestamps are never displayed', () => {
  assert.equal(recentLocation(null, 9000), null);
  assert.equal(recentLocation({at:NaN}, 9000), null);
  assert.equal(recentLocation({at:Infinity}, 9000), null);
  assert.equal(recentLocation({at:11000}, 9000), null);
});
