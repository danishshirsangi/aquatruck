# Multi-business deployment and migration

## Required Firebase setup

1. Enable Firebase Storage in the same Firebase project as AquaTruck.
2. Deploy `firestore.rules` to the named Firestore database configured by the app.
3. Deploy `storage.rules` to the Firebase Storage bucket.
4. Configure a Firestore TTL policy for the `expireAt` field on the `location_points` collection group, selecting the same named database as AquaTruck.
5. The first manager registration imports the locally available pilot fleet into the new business workspace. Any legacy cloud-only records still in the old top-level collections require a one-time Admin SDK migration into the first business's organization subcollections. Verify the copy before retiring the old top-level collections. Do not expose the old global collections to new businesses.

## Firestore TTL

Run this after confirming the active Google Cloud project and database:

```bash
gcloud firestore fields ttls update expireAt \
  --collection-group=location_points \
  --enable-ttl \
  --database=ai-studio-android-aquatrac-ae0f6f83-854d-4712-bbed-b747ec9132c0
```

TTL deletion is asynchronous. Expired movement breadcrumbs can remain queryable for a period after their expiry timestamp.

## Test truck subscription

Truck subscription records are stored at `organizations/{organizationId}/truck_subscriptions/{truckId}` with `mode: test`. The simulated payment makes no real charge and must not be presented as a live payment.

## Security and onboarding

Each registered driver has a unique, one-time invitation code linked to the business, driver's registered phone number, and assigned truck. A code is deactivated after the driver claims it. Share individual codes only with the intended driver.

The manager PIN is an additional in-app access gate. Firebase Authentication and Firestore Security Rules are the actual authorization boundary.
