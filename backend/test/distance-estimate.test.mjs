import test from 'node:test';
import assert from 'node:assert/strict';
import { estimateDistanceMeters, estimateRecentDistance } from '../src/distance-estimate.mjs';
test('same position is zero meters', () => assert.equal(estimateDistanceMeters({lat:0,lon:0},{lat:0,lon:0}),0));
test('known equatorial separation', () => assert.ok(Math.abs(estimateDistanceMeters({lat:0,lon:0},{lat:0,lon:1})-111195)<100));
test('invalid coordinates are rejected', () => assert.equal(estimateDistanceMeters({lat:91,lon:0},{lat:0,lon:0}),null));
test('stale position is not presented as live', () => assert.equal(estimateRecentDistance({lat:0,lon:0,at:0},{lat:0,lon:1,at:29999},30000),null));
test('recent pair returns distance', () => assert.ok(estimateRecentDistance({lat:0,lon:0,at:1000},{lat:0,lon:1,at:1000},2000)>111000));
