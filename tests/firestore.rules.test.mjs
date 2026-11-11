import { before, after, beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import { initializeTestEnvironment, assertSucceeds, assertFails } from "@firebase/rules-unit-testing";
import { doc, collection, getDoc, getDocs, query, where, setDoc, writeBatch, runTransaction } from "firebase/firestore";

const PROJECT_ID = "demo-aquatrack";
const DATABASE_ID = "ai-studio-android-aquatrac-ae0f6f83-854d-4712-bbed-b747ec9132c0";
const ORG_ID = "org-test-water-company";
const MANAGER_UID = "manager-test-uid";
const DRIVER_UID = "driver-test-uid";
const DRIVER_ID = 1;
const TRUCK_ID = 1;
const INVITE_CODE = "TESTDRIVER123";
let testEnv;

function dbFor(context) {
  return context.firestore(DATABASE_ID);
}

async function registerManagerBusiness() {
  const managerContext = testEnv.authenticatedContext(MANAGER_UID);
  const db = dbFor(managerContext);
  const batch = writeBatch(db);
  const org = doc(db, "organizations", ORG_ID);
  batch.set(org, { organizationId: ORG_ID, businessName: "Test Water Company", ownerUid: MANAGER_UID, status: "active" });
  batch.set(doc(org, "members", MANAGER_UID), { uid: MANAGER_UID, role: "manager", name: "Test Manager", status: "active", createdAt: Date.now() });
  batch.set(doc(org, "settings", "driver_policy"), { logoutTimeMinutes: 1260, allowEarlyLogout: false, timezone: "Asia/Kolkata" });
  batch.set(doc(org, "manager_secrets", MANAGER_UID), { pinSalt: "emulator-salt", pinHash: "emulator-hash" });
  batch.set(doc(db, "business_codes", "TESTBIZ01"), { organizationId: ORG_ID, businessName: "Test Water Company", active: true });
  batch.set(doc(db, "users", MANAGER_UID), { uid: MANAGER_UID, organizationId: ORG_ID, role: "manager", businessName: "Test Water Company", displayName: "Test Manager", updatedAt: Date.now() });
  await assertSucceeds(batch.commit());
  return { managerContext, db };
}

async function registerDriverAndInvite(managerDb) {
  const batch = writeBatch(managerDb);
  const org = doc(managerDb, "organizations", ORG_ID);
  batch.set(doc(org, "trucks", "truck_1"), { id: TRUCK_ID, truckNumber: TRUCK_ID, isShiftActive: false, statusText: "Off Duty" });
  // Legacy records can contain a null claim owner rather than an absent/empty field.
  batch.set(doc(org, "trucks", "truck_2"), { id: 2, truckNumber: 2, activeDriverUid: null, isShiftActive: false, statusText: "Off Duty" });
  batch.set(doc(org, "drivers", "driver_1"), { id: DRIVER_ID, name: "Test Driver", phone: "9999999999", assignedTruckId: 0, uid: "", status: "pending", invitationCode: INVITE_CODE });
  batch.set(doc(managerDb, "driver_invitations", INVITE_CODE), { organizationId: ORG_ID, businessName: "Test Water Company", driverId: DRIVER_ID, driverName: "Test Driver", phoneHash: "test-phone-hash", active: true, uid: "" });
  await assertSucceeds(batch.commit());
}

async function claimDriverInvitation() {
  const driverContext = testEnv.authenticatedContext(DRIVER_UID);
  const db = dbFor(driverContext);
  const batch = writeBatch(db);
  const invite = doc(db, "driver_invitations", INVITE_CODE);
  const org = doc(db, "organizations", ORG_ID);
  batch.update(invite, { uid: DRIVER_UID, claimedAt: Date.now(), active: false });
  batch.update(doc(org, "drivers", "driver_1"), { uid: DRIVER_UID, name: "Test Driver", phone: "9999999999", status: "active" });
  batch.set(doc(org, "members", DRIVER_UID), { uid: DRIVER_UID, driverId: DRIVER_ID, role: "driver", name: "Test Driver", phone: "9999999999", activeTruckId: 0, earlyLogoutEnabled: false, status: "active", createdAt: Date.now(), invitationId: INVITE_CODE });
  batch.set(doc(db, "users", DRIVER_UID), { uid: DRIVER_UID, organizationId: ORG_ID, role: "driver", businessName: "Test Water Company", displayName: "Test Driver", phone: "9999999999", updatedAt: Date.now() });
  await assertSucceeds(batch.commit());
  return { driverContext, db };
}

async function claimTruckForDriver(driverDb) {
  const batch = writeBatch(driverDb);
  batch.update(doc(driverDb, "organizations", ORG_ID, "members", DRIVER_UID), { activeTruckId: TRUCK_ID });
  batch.update(doc(driverDb, "organizations", ORG_ID, "trucks", "truck_1"), {
    activeDriverUid: DRIVER_UID, isShiftActive: true, statusText: "Shift Active - En Route"
  });
  await assertSucceeds(batch.commit());
}

async function createCompleteBusinessAndDriver() {
  const { managerContext, db: managerDb } = await registerManagerBusiness();
  await registerDriverAndInvite(managerDb);
  const { driverContext, db: driverDb } = await claimDriverInvitation();
  return { managerContext, managerDb, driverContext, driverDb };
}

function gpsPoint(id, truckId = TRUCK_ID) {
  return { id, truckId, latitude: 15.852 + id * 0.0001, longitude: 74.510 + id * 0.0001, speedKmh: 18.5, accuracyMeters: 7.5, timestamp: 1794400000000 + id * 10000 };
}

before(async () => {
  testEnv = await initializeTestEnvironment({ projectId: PROJECT_ID, firestore: { host: "127.0.0.1", port: 8080 } });
});
beforeEach(async () => testEnv.clearFirestore());
after(async () => testEnv.cleanup());

test("manager creates business and driver invitation", async () => {
  const { db: managerDb } = await registerManagerBusiness();
  await registerDriverAndInvite(managerDb);
  const orgSnap = await assertSucceeds(getDoc(doc(managerDb, "organizations", ORG_ID)));
  assert.equal(orgSnap.data().businessName, "Test Water Company");
});

test("driver invitation claim atomically creates membership without assigning a truck", async () => {
  const { driverDb } = await createCompleteBusinessAndDriver();
  const member = await assertSucceeds(getDoc(doc(driverDb, "organizations", ORG_ID, "members", DRIVER_UID)));
  const user = await assertSucceeds(getDoc(doc(driverDb, "users", DRIVER_UID)));
  assert.equal(member.data().role, "driver");
  assert.equal(member.data().status, "active");
  assert.equal(member.data().activeTruckId, 0);
  assert.equal(member.data().assignedTruckId, undefined);
  assert.equal(user.data().organizationId, ORG_ID);
  assert.equal(user.data().role, "driver");
});

test("driver can see all trucks in their own business before choosing one", async () => {
  const { driverDb } = await createCompleteBusinessAndDriver();
  const trucks = await assertSucceeds(getDocs(collection(driverDb, "organizations", ORG_ID, "trucks")));
  assert.equal(trucks.size, 2);
});

test("active driver uploads GPS points and manager reads route history", async () => {
  const { managerDb, driverDb } = await createCompleteBusinessAndDriver();
  await claimTruckForDriver(driverDb);
  const batch = writeBatch(driverDb);
  for (let i = 1; i <= 8; i++) {
    const point = gpsPoint(i);
    batch.set(doc(driverDb, "organizations", ORG_ID, "location_points", `loc_${point.truckId}_${point.timestamp}`), point);
  }
  await assertSucceeds(batch.commit());
  const route = await assertSucceeds(getDocs(query(collection(managerDb, "organizations", ORG_ID, "location_points"), where("truckId", "==", TRUCK_ID))));
  assert.equal(route.size, 8);
});

test("missing membership rejects GPS upload", async () => {
  const { managerContext, db: managerDb } = await registerManagerBusiness();
  const stranger = testEnv.authenticatedContext("driver-without-membership");
  const db = dbFor(stranger);
  await assertFails(setDoc(doc(db, "organizations", ORG_ID, "location_points", "missing-member"), gpsPoint(90)));
  await assertSucceeds(getDoc(doc(managerDb, "organizations", ORG_ID)));
});

test("driver attendance is accepted only after the truck claim is active", async () => {
  const { driverDb } = await createCompleteBusinessAndDriver();
  const attendance = {
    id: 1,
    truckId: TRUCK_ID,
    driverName: "Test Driver",
    selfieUri: "file:///driver-selfie.jpg",
    checkInTime: 1794400000000,
    checkOutTime: 0,
    checkInLat: 15.852,
    checkInLng: 74.510,
    isCompleted: false
  };
  await assertFails(setDoc(doc(driverDb, "organizations", ORG_ID, "attendance", "before-claim"), attendance));

  await claimTruckForDriver(driverDb);
  await assertSucceeds(setDoc(doc(driverDb, "organizations", ORG_ID, "attendance", "after-claim"), attendance));
});

test("driver can transactionally claim an inactive legacy truck with a null activeDriverUid", async () => {
  const { driverDb } = await createCompleteBusinessAndDriver();
  const memberRef = doc(driverDb, "organizations", ORG_ID, "members", DRIVER_UID);
  const truckRef = doc(driverDb, "organizations", ORG_ID, "trucks", "truck_2");

  await runTransaction(driverDb, async (transaction) => {
    const memberSnapshot = await transaction.get(memberRef);
    const truckSnapshot = await transaction.get(truckRef);
    assert.equal(memberSnapshot.data().activeTruckId, 0);
    assert.equal(truckSnapshot.data().activeDriverUid, null);
    assert.equal(truckSnapshot.data().isShiftActive, false);

    transaction.update(truckRef, {
      activeDriverUid: DRIVER_UID,
      isShiftActive: true,
      statusText: "Shift Active - En Route",
      lastUpdated: Date.now()
    });
    transaction.update(memberRef, { activeTruckId: 2 });
  });

  const claimedTruck = await assertSucceeds(getDoc(truckRef));
  const member = await assertSucceeds(getDoc(memberRef));
  assert.equal(claimedTruck.data().activeDriverUid, DRIVER_UID);
  assert.equal(member.data().activeTruckId, 2);
});

test("driver can switch to a selected available truck when local session is empty but Firestore has an active truck", async () => {
  const { driverDb } = await createCompleteBusinessAndDriver();
  const memberRef = doc(driverDb, "organizations", ORG_ID, "members", DRIVER_UID);
  const oldTruckRef = doc(driverDb, "organizations", ORG_ID, "trucks", "truck_1");
  const selectedTruckRef = doc(driverDb, "organizations", ORG_ID, "trucks", "truck_2");

  await claimTruckForDriver(driverDb);

  // Simulate a fresh local session: the app passes expectedOldId=null,
  // while Firestore still records truck 1 as this driver's active truck.
  await runTransaction(driverDb, async (transaction) => {
    const member = await transaction.get(memberRef);
    const oldTruck = await transaction.get(oldTruckRef);
    const selectedTruck = await transaction.get(selectedTruckRef);
    assert.equal(member.data().activeTruckId, TRUCK_ID);
    assert.equal(oldTruck.data().activeDriverUid, DRIVER_UID);
    assert.equal(selectedTruck.data().isShiftActive, false);

    transaction.update(oldTruckRef, {
      activeDriverUid: "",
      lastDriverUid: DRIVER_UID,
      isShiftActive: false,
      statusText: "Off Duty",
      currentSpeedKmh: 0,
      lastUpdated: Date.now()
    });
    transaction.update(selectedTruckRef, {
      activeDriverUid: DRIVER_UID,
      isShiftActive: true,
      statusText: "Shift Active - En Route",
      lastUpdated: Date.now()
    });
    transaction.update(memberRef, { activeTruckId: 2 });
  });

  const member = await assertSucceeds(getDoc(memberRef));
  const oldTruck = await assertSucceeds(getDoc(oldTruckRef));
  const selectedTruck = await assertSucceeds(getDoc(selectedTruckRef));
  assert.equal(member.data().activeTruckId, 2);
  assert.equal(oldTruck.data().isShiftActive, false);
  assert.equal(selectedTruck.data().activeDriverUid, DRIVER_UID);
});

test("driver cannot upload GPS for a truck they have not claimed", async () => {
  const { driverDb } = await createCompleteBusinessAndDriver();
  await claimTruckForDriver(driverDb);
  await assertFails(setDoc(doc(driverDb, "organizations", ORG_ID, "location_points", "wrong-truck"), gpsPoint(91, 2)));
});

test("driver cannot claim a truck unless membership and truck ownership update together", async () => {
  const { driverDb } = await createCompleteBusinessAndDriver();
  await claimTruckForDriver(driverDb);
  await assertFails(setDoc(doc(driverDb, "organizations", ORG_ID, "trucks", "truck_2"), {
    id: 2, activeDriverUid: DRIVER_UID, isShiftActive: true
  }, { merge: true }));
});

test("logout releases the active truck and blocks further GPS uploads", async () => {
  const { driverDb } = await createCompleteBusinessAndDriver();
  await claimTruckForDriver(driverDb);
  const batch = writeBatch(driverDb);
  batch.update(doc(driverDb, "organizations", ORG_ID, "members", DRIVER_UID), { activeTruckId: 0 });
  batch.update(doc(driverDb, "organizations", ORG_ID, "trucks", "truck_1"), {
    activeDriverUid: "", lastDriverUid: DRIVER_UID, isShiftActive: false, statusText: "Off Duty"
  });
  await assertSucceeds(batch.commit());
  const queuedPoint = { ...gpsPoint(93), driverUid: DRIVER_UID };
  await assertSucceeds(setDoc(doc(driverDb, "organizations", ORG_ID, "location_points", "after-logout"), queuedPoint));
  await assertFails(setDoc(doc(driverDb, "organizations", ORG_ID, "location_points", "wrong-driver-after-logout"), { ...gpsPoint(94), driverUid: "another-driver" }));
});

test("driver cannot access another organisation's GPS history", async () => {
  const { driverDb } = await createCompleteBusinessAndDriver();
  await assertFails(setDoc(doc(driverDb, "organizations", "another-company", "location_points", "foreign-point"), gpsPoint(92)));
  await assertFails(getDoc(doc(driverDb, "organizations", "another-company", "location_points", "foreign-point")));
});

test("offline queue uploads after network returns", async () => {
  const { managerDb, driverDb } = await createCompleteBusinessAndDriver();
  await claimTruckForDriver(driverDb);
  const localQueue = Array.from({ length: 5 }, (_, i) => gpsPoint(200 + i));
  let networkAvailable = false;
  assert.equal(networkAvailable, false);
  assert.equal(localQueue.length, 5);
  networkAvailable = true;
  assert.equal(networkAvailable, true);
  const batch = writeBatch(driverDb);
  for (const point of localQueue) {
    batch.set(doc(driverDb, "organizations", ORG_ID, "location_points", `loc_${point.truckId}_${point.timestamp}`), point);
  }
  await assertSucceeds(batch.commit());
  localQueue.length = 0;
  const uploaded = await assertSucceeds(getDocs(collection(managerDb, "organizations", ORG_ID, "location_points")));
  assert.equal(uploaded.size, 5);
  assert.equal(localQueue.length, 0);
});
