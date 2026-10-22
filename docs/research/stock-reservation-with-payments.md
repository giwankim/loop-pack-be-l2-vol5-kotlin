# Stock reservation and the payment outcome: how commerce systems connect them

Researched on 2026-10-22 for week 5 (PG payment). The question: how do real commerce engines and platforms tie inventory to payment, and how do they implement stock reservation? Of most interest: what they do when the payment outcome is pending or unknown.

Sources are primary only:

- Source code of five open-source engines, read at release tags: Saleor `3.23.44`, Medusa `v2.22.0`, Magento MSI `1.2.9` with Magento `2.4.9`, Spree `v5.6.1`, Solidus `v4.8.0`, and WooCommerce `11.2.1`.
- Official docs from Shopify, commercetools, Stripe, Adyen, Toss Payments and Amazon.
- First-party engineering blogs (Shopify, 우아한형제들, 컬리, 올리브영, 29CM).
- microservices.io, the Eventuate example code, and the Temporal docs.

Docs pages were read on 2026-10-22. Anything not stated by a source is marked *inference*. Section 8 relates the findings to [ADR 0021](../adr/0021-payment-is-its-own-aggregate-and-slice.md), [ADR 0022](../adr/0022-only-the-pre-create-rejection-proves-no-pg-transaction.md), [ADR 0023](../adr/0023-pg-port-returns-transactions-and-throws-the-rest.md) and `CONTEXT.md`, and is mostly *inference*.

## Summary

1. **Holds come in two kinds.**
   - A short pre-order hold with a TTL in minutes: Spree 10 min, WooCommerce 60 min, Buy with Prime 10 min, Saleor's opt-in checkout hold plus a 45 s hold for the duration of the payment call, Shopify's "several minutes" hold while payment runs, and commercetools `ReserveOnCart`.
   - A durable order-level hold with no TTL, which lasts until fulfilment or cancellation: Magento's order reservation, Medusa's `ReservationItem`, Saleor's `Allocation`, Shopify's `committed`, and commercetools `ReserveOnOrder`.
   - What a system does with a pending payment depends on which of the two holds the stock at that moment.
2. **Data models.** There are four shapes:
   - a counter on the stock row (Saleor `quantity_allocated`, Medusa `reserved_quantity`);
   - reservation rows with an expiry (Saleor `Reservation.reserved_until`, Spree `expires_at`, WooCommerce `expires`);
   - an append-only signed ledger (Magento `inventory_reservation`);
   - one row per sellable unit, claimed with `SKIP LOCKED` (Shopify, 2026).
3. **Concurrency.** Every engine whose code I read serialises the availability check with a lock: `SELECT … FOR UPDATE` on stock rows in Saleor, Spree and WooCommerce, an application lock in Medusa, and MySQL `GET_LOCK` in Magento. Two details stand out:
   - Magento's 2018 design said append-only reservations need "no checks and blocking operations (locks)". In February 2026 Adobe added a per-SKU lock, in a commit titled "Magento allows overselling when multiple customers place orders concurrently".
   - Shopify replaced Redis `DECR`/`INCR` with MySQL because Redis could not commit the claim atomically with the inventory ledger.
4. **Expiry is lazy, and the sweeper only cleans up.** Availability queries filter on `expires > now` (Saleor, Spree, WooCommerce). The scheduled job is housekeeping: Saleor runs it daily, and Spree recommends every minute but does not schedule it. Release is usually idempotent because it deletes rows by order key. Magento instead appends a compensating row, which is not idempotent by construction (*inference*).
5. **Unknown or pending payment (section 5).** No system I read keeps a *TTL* hold open indefinitely because the payment outcome is unknown. They do one of three things:
   - **Promote** the hold to an order-level hold while a PSP-acknowledged payment is pending. Examples: Magento `pending_payment` orders, Shopify "Pending" orders ("inventory is reserved for the order, but payment isn't guaranteed"), commercetools Checkout creating the Order while authorisation is `Pending`, Medusa `pending_authorization`, and WooCommerce `on-hold`. Most bound this with an order-level deadline: Magento cancels after 480 min by default, WooCommerce after 60 min, and Shopify recommends a pending expiry within 3 days. Two do not: Medusa has no expiry for such orders, and Saleor's order-expiry job explicitly skips orders that have any payment.
   - **Treat a transport failure as failure** and release. Examples: Saleor's legacy gateway path, Medusa (any non-pending error triggers compensation), and WooCommerce's Store API (any exception releases). The safety net for a payment that actually succeeded is a webhook-driven late completion plus an automatic refund or void.
   - **Let the TTL lapse** and accept the consequence: Spree and Saleor's 45 s hold.
6. **Late success after release.**
   - Automatic refund or void: Saleor legacy, Medusa, and Saleor's opt-in "release funds for expired checkouts".
   - Optional automatic reversal: commercetools.
   - Stock goes negative: WooCommerce decrements without a floor.
   - Left to humans: Spree reports the error; commercetools says "it's your responsibility".
   - Shopify prevents the case for offsite payment apps. Before an app may finalise a payment, it must ask Shopify to confirm inventory. Toss's authenticate → confirm split, with a 10-minute window, gives a merchant the same chance.
7. **Authorisation and hold lifetimes differ.** An authorisation lasts days: Stripe and Shopify Payments 7 days for online cards, Adyen 28 days by default with scheme-specific limits. Cart holds last minutes. Systems reconcile the two by turning the short hold into an order-level allocation when the order is placed, then capturing at or before shipment inside the authorisation window. Amazon charges at shipment, or five days after the order at the latest.
8. **The assignment's model.**
   - The reserve → PG call → apply shape matches Saleor's legacy three-transaction checkout almost exactly. Saleor even comments that its 45 s reservation exists "to mimic stock lock" across the split.
   - Keeping the hold on UNKNOWN matches industry practice for *pending* payments.
   - It differs in one respect: every comparable hold I found has some bound, whether a TTL, an order-expiry job, or a PSP-declared expiry. The assignment's UNKNOWN with zero PG transactions has none. That is the cost already recorded in [ADR 0022](../adr/0022-only-the-pre-create-rejection-proves-no-pg-transaction.md) (*inference*).

## 1. When stock is reserved

| System | Reserve point | Hold length | Stated trade-off |
| --- | --- | --- | --- |
| Saleor | Checkout create or line add, opt-in. At `checkoutComplete` with legacy payments, an extra hold for the payment call. At order creation, an `Allocation`. | Minutes, separately for anonymous and authenticated users; both `null` (off) by default. The payment-call hold is 45 s. The allocation has no TTL. | Without reservations, "no guarantee is made to the client that stock will not be sold out before checkout completion", and losers get `INSUFFICIENT_STOCK` at `checkoutComplete`. With them, an expired hold makes the items "available" to others again. |
| Medusa v2 | `completeCartWorkflow`: the order is created, inventory reserved, payment authorised last | none | Payment is authorised "at the very end of the workflow to minimize the risk of canceling the payment in the compensation flow" |
| Magento MSI | Order placement, before the order is saved and before the payment method runs | none (order-level). A `pending_payment` order is cancelled by cron after 480 min by default. | Append-only so that checkout avoids "blocking operations and race conditions". A cart reservation ("say, reservation for 15 minutes") is named as backlog, not built. |
| Spree 5.5+ | Entering checkout (cart → address); each later checkout mutation extends it | 10 min default; store and global setting | "Two customers can no longer both pass the availability check on the same last unit only to have one of them fail at order completion." |
| Solidus 4.8 | No reservation. Availability is checked before payment; stock is decremented in `finalize` after payment. | n/a | none stated |
| WooCommerce | Checkout POST, once the order exists, before payment | `woocommerce_hold_stock_minutes`, default 60 | "Hold stock (for unpaid orders) for x minutes. When this limit is reached, the pending order will be cancelled." |
| Shopify | "When payment starts" (reserve); "when payment succeeds" (claim). Orders then hold units as `committed` until fulfilment. | "a short hold, e.g. several minutes" | Wrong in one direction means oversell ("cancel an order, send an apology email"). Wrong in the other means undersell ("the merchant loses a sale"). |
| commercetools | Chosen per cart or line item: `None`, `TrackOnly`, `ReserveOnOrder` or `ReserveOnCart` | `ReserveOnCart`: 1 to 44,640 min (31 days), set per project and optionally per inventory entry | `TrackOnly` "allows for overselling". `ReserveOnOrder` suits "businesses that want to avoid holding stock before the order is finalized". `ReserveOnCart` suits "high-demand products". |
| Stripe Checkout (payment side) | The merchant reserves; Stripe expires the session | `expires_at` 30 min to 24 h, default 24 h | An event-ticket seller wants to "allow customers only a few minutes to buy their selected tickets" |
| Amazon | Buy with Prime: at "Place order". amazon.com: card authorised at order, charged at shipment. | Buy with Prime: 10 min | The cart reserves nothing: "Placing an item in your cart doesn't reserve the price shown at that time." |

Sources:

- **Saleor:**
  - [stock reservation docs](https://docs.saleor.io/developer/stock/stock-reservation) and [checkout stock docs](https://docs.saleor.io/developer/checkout/stock)
  - code: [`site/models.py`](https://github.com/saleor/saleor/blob/3.23.44/saleor/site/models.py#L82-L86), [`checkout_lines_add.py`](https://github.com/saleor/saleor/blob/3.23.44/saleor/graphql/checkout/mutations/checkout_lines_add.py#L134), [`settings.py` `RESERVE_DURATION`](https://github.com/saleor/saleor/blob/3.23.44/saleor/settings.py#L979-L982)
- **Medusa:** [`complete-cart.ts`](https://github.com/medusajs/medusa/blob/v2.22.0/packages/core/core-flows/src/cart/workflows/complete-cart.ts#L648-L690)
- **Magento:** the [MSI reservations design doc](https://github.com/magento/inventory/wiki/Salable-Quantity-Calculation-and-Mechanism-of-Reservations) (wiki commit `39310ad`)
- **Spree:** [inventory docs at v5.6.1](https://github.com/spree/spree/blob/v5.6.1/docs/developer/core-concepts/inventory.mdx)
- **Solidus:** [order state machine](https://github.com/solidusio/solidus/blob/v4.8.0/core/app/models/spree/core/state_machines/order/class_methods.rb#L91-L103)
- **WooCommerce:** [settings](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/includes/admin/settings/class-wc-settings-products.php#L261-L274) and [Store API checkout](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/src/StoreApi/Routes/V1/Checkout.php#L624-L650)
- **Shopify:** the [engineering blog, 2026-05-12](https://shopify.engineering/scaling-inventory-reservations)
- **commercetools:** the [inventory overview](https://docs.commercetools.com/api/inventory-overview) and the [reserve-on-cart tutorial](https://docs.commercetools.com/tutorials/reserve-stock-on-cart)
- **Stripe:** [managing limited inventory](https://docs.stripe.com/payments/checkout/managing-limited-inventory)
- **Amazon:** [Buy with Prime checkout](https://buywithprime.amazon.com/knowledge-center/checkout) and [Shopping Cart Prices](https://www.amazon.com/gp/help/customer/display.html?nodeId=GF3GA88LAZ36ZMSQ)

Saleor's two holds deserve a note. The checkout hold (`reserveStockDurationAnonymousUser` / `…AuthenticatedUser`, in minutes) is a shopping-time convenience. The 45 s hold is added in `complete_checkout_with_payment` whether or not checkout holds are enabled, and settings.py explains why: "Since we split checkout complete logic into two separate transactions, in order to mimic stock lock, we apply short reservation for the stocks."

## 2. The reservation data model

### 2.1 Counter on the stock row

- **Saleor:**
  - `Stock(warehouse, product_variant, quantity, quantity_allocated)`, unique on `(warehouse, product_variant)`.
  - Orders hold stock through `Allocation(order_line, stock, quantity_allocated)`, unique on `(order_line, stock)`, which is mirrored into the `Stock.quantity_allocated` counter ([`warehouse/models.py`](https://github.com/saleor/saleor/blob/3.23.44/saleor/warehouse/models.py#L527-L593)).
  - Fulfilment decreases `quantity`; cancellation deallocates.
- **Medusa:**
  - `InventoryLevel(location_id, stocked_quantity, reserved_quantity, incoming_quantity)` per item and location, plus a computed `available_quantity = stocked_quantity − reserved_quantity` ([`inventory-level.ts`](https://github.com/medusajs/medusa/blob/v2.22.0/packages/modules/inventory/src/models/inventory-level.ts), [`apply-decorators.ts`](https://github.com/medusajs/medusa/blob/v2.22.0/packages/modules/inventory/src/utils/apply-decorators.ts#L12-L22)).
  - The counter is maintained only through reservation rows. The service strips `reserved_quantity` from direct updates: "reserved_quantity should solely be handled through creating & updating reservation items" ([`inventory-module.ts`](https://github.com/medusajs/medusa/blob/v2.22.0/packages/modules/inventory/src/services/inventory-module.ts#L197-L209)).
- **Shopify** exposes states rather than one number: `available`, `committed`, `reserved`, `damaged`, `safety_stock`, `quality_control`, `on_hand` and `incoming`.
  - `on_hand` is the sum of the six non-incoming states.
  - At order creation Shopify "decrements the available inventory quantity", and on fulfilment it "decrements the committed inventory quantity".
  - Sources: [inventory management apps](https://shopify.dev/docs/apps/build/orders-fulfillment/inventory-management-apps), [inventory states](https://help.shopify.com/en/manual/products/inventory/managing-inventory-quantities/inventory-states).

### 2.2 Reservation rows with an expiry

| System | Table / model | Key and uniqueness | Expiry column |
| --- | --- | --- | --- |
| Saleor | `Reservation(checkout_line, stock, quantity_reserved, reserved_until)`; `PreorderReservation` for pre-orders | unique `(checkout_line, stock)`; index `(checkout_line, reserved_until)` | `reserved_until` |
| Spree 5.5 | `spree_stock_reservations(stock_item_id, line_item_id, order_id, quantity, expires_at)` | unique `(stock_item_id, line_item_id)`; index `(stock_item_id, expires_at)` | `expires_at` |
| WooCommerce | `wc_reserved_stock(order_id, product_id, stock_quantity, timestamp, expires)` | PK `(order_id, product_id)` "to prevent multiple holds being created for the same order if checkout is performed multiple times" | `expires` |
| Medusa | `ReservationItem(line_item_id, location_id, quantity, allow_backorder, inventory_item, …)` | indexes on `line_item_id`, `location_id`, `inventory_item_id` | none: no TTL column |
| commercetools | `Reservation`, reachable only by expanding the line item's `reservation` reference ("There is no dedicated API endpoint to query or manage Reservation resources directly") | per line item | project or inventory-entry setting |

Sources:

- **Saleor:** [`warehouse/models.py`](https://github.com/saleor/saleor/blob/3.23.44/saleor/warehouse/models.py#L622-L690)
- **Spree:** [migration](https://github.com/spree/spree/blob/v5.6.1/spree/core/db/migrate/20260501000001_create_spree_stock_reservations.rb) and [model](https://github.com/spree/spree/blob/v5.6.1/spree/core/app/models/spree/stock_reservation.rb)
- **WooCommerce:** [install schema](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/includes/class-wc-install.php#L2064-L2072) and the [blocks stock-reservation doc](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/client/blocks/docs/internal-developers/blocks/stock-reservation.md)
- **Medusa:** [`reservation-item.ts`](https://github.com/medusajs/medusa/blob/v2.22.0/packages/modules/inventory/src/models/reservation-item.ts)
- **commercetools:** the [inventory overview](https://docs.commercetools.com/api/inventory-overview)

Medusa keeps both a row and the counter: creating a `ReservationItem` adds its quantity to `InventoryLevel.reserved_quantity` in the same transaction.

### 2.3 Append-only signed ledger (Magento MSI)

```xml
<table name="inventory_reservation">
  reservation_id INT identity, stock_id INT, sku VARCHAR(64),
  quantity DECIMAL(10,4) signed, metadata VARCHAR(255)
  INDEX (stock_id, sku, quantity)
</table>
```

([`db_schema.xml`](https://github.com/magento/inventory/blob/1.2.9/InventoryReservations/etc/db_schema.xml))

- Salable quantity is "StockItem Qty (qty from Stock Item index) + All Reservations created for given SKU on the given StockId".
- Placing an order appends `-30`. Cancelling, shipping or refunding appends compensating `+` rows, so that "when the order will come to the finite state (complete|canceled) - the sum of all created reservations while working with it should be ZERO".
- "New Inventory infrastructure doesn't remove or modify already created reservations". There are deliberately no reservation statuses.
- `metadata` carries the business event, for example `{"event_type":"order_placed","object_type":"order","object_id":"8"}`.

Source: [design doc](https://github.com/magento/inventory/wiki/Salable-Quantity-Calculation-and-Mechanism-of-Reservations).

### 2.4 One row per sellable unit (Shopify, 2026)

> "Instead of one row per item with a quantity column, we use one row per sellable unit. An item with 10 units has 10 rows."

- The pool is capped at 1,000 rows per item/location and refilled from the ledger.
- Reserve deletes rows from `reservation_units` and inserts into `reserved_quantities`; claim touches only `reserved_quantities`.
- Earlier attempts failed: "A single row with a quantity column couldn't handle the contention."

Source: [Shopify Engineering](https://shopify.engineering/scaling-inventory-reservations).

### 2.5 Other shapes in first-party sources

- **배민 선물하기** (2021) keeps total stock in MySQL and usage in a Redis **Set of purchase numbers**: `SADD … stock:total G0AA0001JR` to reserve, `SREM` to release, `SCARD` to count. Every change is also inserted into a `stock_history(purchaseNumber, type PLUS|MINUS)` table ([우아한형제들 기술블로그](https://techblog.woowahan.com/2709/)).
- **Eventuate saga example:** `Customer` holds `Map<Long, Money> creditReservations` keyed by `orderId`, with `@Version`. `availableCredit() = creditLimit − sum(reservations)` ([`Customer.java`](https://github.com/eventuate-tram/eventuate-tram-sagas-examples-customers-and-orders/blob/894f2dbbe9aba2be6129748b6bbf5f30356deb07/customer-service/customer-service-domain/src/main/java/io/eventuate/examples/tram/sagas/customersandorders/customers/domain/Customer.java)).

## 3. Concurrency control

| System | Mechanism | Where the consistency boundary sits |
| --- | --- | --- |
| Saleor | `Stock … select_for_update(of=self).order_by("pk")`. Availability = `quantity − Σ allocations − Σ not-expired reservations of other checkouts`, computed under the lock. | One DB transaction per reserve or allocate. The payment call has its own transaction, which locks the checkout and payment rows but **not** stock rows ("Process payments out of transaction to unlock stock rows for another user"). |
| Spree 5.5 | `StockItem.where(id: …).lock` (FOR UPDATE), then `available = count_on_hand − held_by_others − this_order_used` | Reserve transaction. Note: `Carts::Complete` calls `process_payments!` inside `cart.with_lock`, so the gateway call runs while the order row is locked. |
| WooCommerce | One statement: `INSERT INTO wc_reserved_stock … SELECT … WHERE (stock FOR UPDATE) − (reserved LOCK IN SHARE MODE) >= qty ON DUPLICATE KEY UPDATE expires = …`, retried up to 3 times on deadlock or lock-wait errors | One atomic statement per product |
| Medusa | Locking module, keyed by inventory item ids, around read → check `available_quantity` → create rows → bump `reserved_quantity`. The default provider is in-memory; Redis and Postgres providers exist. The cart itself is locked by id (30 s wait, 2 min TTL). | Application lock plus a DB transaction. *Inference:* the in-memory default only serialises within one process. |
| Magento MSI | **2018 design:** "Reservation creation is append-only operation, so there are no checks and blocking operations (locks) needed." **2026:** `GET_LOCK(sku+stock, 10)` around `placeOrder`, added in commit [`40a2ac4`](https://github.com/magento/inventory/commit/40a2ac4be9953c66b2839778e34e25c8539abb88) (2026-02-06), titled "ACP2E-4509: Magento allows overselling when multiple customers place orders concurrently" | MySQL named lock per SKU, held for the whole `placeOrder`. *Inference from the call chain:* `QuoteManagement::placeOrder → submit → OrderManagement::place → Order::place → _placePayment`, so the lock spans the payment method's call. |
| Shopify | `SELECT … FOR UPDATE SKIP LOCKED` over unit rows; composite PK `(shop_id, inventory_item_id, inventory_group_id, id)` for one lock per row; `READ COMMITTED` to avoid gap locks; a fixed table lock order between reserve and claim; `UNION ALL` batching | Reservations live in the same MySQL database as the inventory ledger, so "we get ACID across reserve and claim". Redis could not do this ("couldn't be wrapped in a single atomic step"). |
| commercetools | "The reservation logic acquires stock from an internal real-time SKU availability collection" | `InventoryEntry.availableQuantity` is eventually consistent, taking "up to 10 seconds" to appear, but "Reservations are guaranteed regardless of the eventual consistency delay" |
| Solidus | No reservation. `StockItem#adjust_count_on_hand` uses `with_lock`; `count_on_hand >= 0` is validated unless the item is backorderable. The order state machine has `use_transactions: false`. | *Inference:* the availability check runs before payment and the decrement after it, so a race surfaces after payment |
| 배민 선물하기 | Redis single-threaded commands plus `MULTI/EXEC` for the total and per-member sets; `@Synchronized` on the service method | *Inference:* `@Synchronized` is per JVM, so the `SCARD`-then-`SADD` check is atomic only within one instance |
| 컬리 VSMS | All cart items are decremented in one transaction; deadlocks were fixed by **sorting** the items, "3줄짜리 변경사항" | One transaction per order |
| 올리브영 | Order completion's stock updates were split into per-item transactions ("각각 바로 Commit이 되도록 Transaction을 나누었고") to end deadlocks | Per item. The cost the post mentions is rollback handling across items. |
| 29CM | Stock was moved out of the option table into its own table, because order decrements and admin product edits contended for the same row lock | Separate stock row |

Sources:

- **Saleor:** [`reservations.py`](https://github.com/saleor/saleor/blob/3.23.44/saleor/warehouse/reservations.py#L91-L188), [`lock_objects.py`](https://github.com/saleor/saleor/blob/3.23.44/saleor/warehouse/lock_objects.py), [`complete_checkout.py`](https://github.com/saleor/saleor/blob/3.23.44/saleor/checkout/complete_checkout.py#L1852-L1987)
- **Spree:** [`reserve.rb`](https://github.com/spree/spree/blob/v5.6.1/spree/core/app/services/spree/stock_reservations/reserve.rb), [`carts/complete.rb`](https://github.com/spree/spree/blob/v5.6.1/spree/core/app/services/spree/carts/complete.rb#L10-L31)
- **WooCommerce:** [`ReserveStock.php`](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/src/Checkout/Helpers/ReserveStock.php#L215-L245)
- **Medusa:** [`reserve-inventory.ts`](https://github.com/medusajs/medusa/blob/v2.22.0/packages/core/core-flows/src/cart/steps/reserve-inventory.ts#L63-L117), [`inventory-module.ts`](https://github.com/medusajs/medusa/blob/v2.22.0/packages/modules/inventory/src/services/inventory-module.ts#L173-L307), [locking providers](https://github.com/medusajs/medusa/blob/v2.22.0/packages/modules/locking/src/loaders/providers.ts#L56-L60)
- **Magento:**
  - [`CartManagementPlugin.php`](https://github.com/magento/inventory/blob/1.2.9/InventorySales/Plugin/Quote/CartManagementPlugin.php#L92-L140), [`AcquireInventoryLock.php`](https://github.com/magento/inventory/blob/1.2.9/InventorySales/Model/ResourceModel/AcquireInventoryLock.php)
  - Magento `2.4.9`: [`QuoteManagement.php`](https://github.com/magento/magento2/blob/2.4.9/app/code/Magento/Quote/Model/QuoteManagement.php#L400), [`OrderService.php`](https://github.com/magento/magento2/blob/2.4.9/app/code/Magento/Sales/Model/Service/OrderService.php#L261-L282), [`Order.php`](https://github.com/magento/magento2/blob/2.4.9/app/code/Magento/Sales/Model/Order.php#L1281-L1284)
- **Shopify:** [Engineering](https://shopify.engineering/scaling-inventory-reservations)
- **commercetools:** [inventory overview](https://docs.commercetools.com/api/inventory-overview)
- **Solidus:** [`stock_item.rb`](https://github.com/solidusio/solidus/blob/v4.8.0/core/app/models/spree/stock_item.rb#L13-L45), [state machine](https://github.com/solidusio/solidus/blob/v4.8.0/core/app/models/spree/core/state_machines/order/class_methods.rb#L38)
- **Korean blogs:** [배민](https://techblog.woowahan.com/2709/), [컬리](https://helloworld.kurly.com/blog/vsms-performance-experiment/), [올리브영 Part 3](https://oliveyoung.tech/2022-12-13/oliveyoung-transaction-orderstock/), [29CM](https://medium.com/29cm/%EC%85%80%EB%A0%89%ED%8A%B8%EC%83%B5%EC%97%90%EC%84%9C-%EC%98%88%EC%95%BD%ED%95%98%EA%B8%B0-%EC%84%9C%EB%B9%84%EC%8A%A4-%EA%B0%9C%EB%B0%9C%EA%B8%B0-f7578ffcfbf7)

Two engines do the opposite of the assignment's "PG call outside any transaction":

- Spree runs the gateway call under the order row lock.
- Magento (since 2026) runs it under a per-SKU named lock.

Saleor and Shopify keep stock locks short and leave the PSP call outside them.

## 4. Expiry and release

| System | TTL | Who sweeps | Read-time filter | Release and its "once" guarantee |
| --- | --- | --- | --- | --- |
| Saleor | Checkout hold: configured minutes. Payment-call hold: 45 s. | Celery beat `delete-expired-reservations`, **daily**, deletes rows with `reserved_until < now` | `not_expired()` on every availability query | Rows are deleted (re-reserving replaces them by checkout line). Checkout deletion after order creation cascades. Order cancel deallocates under `allocation_with_stock_qs_select_for_update`. |
| Spree 5.5 | 10 min default, per store or global | `StockReservations::ExpireJob` deletes expired rows in batches of 1,000. Spree "does **not** auto-schedule" it; the app's job runner should call it, "every minute is the recommended cadence". | The Quantifier "still ignores them at availability-check time" | `Release` = `delete_all where order_id` (idempotent by construction) |
| WooCommerce | 60 min default | Expired rows "are cleaned up periodically". Separately, `wc_cancel_unpaid_orders` reschedules itself every hold-minutes and cancels pending orders created by checkout or the Store API: "Unpaid order cancelled - time limit reached." | `expires > NOW()` in the reserved-stock subquery | `release_stock_for_order` deletes by `order_id`. Consumption runs once: per-item `_reduced_stock` meta plus an order-level `_order_stock_reduced` flag. |
| commercetools | 1 to 44,640 min | Platform-managed. "To prevent Order creation failures due to a last-second expiration, the platform automatically attempts to renew expired Cart reservations during the Order creation process." | n/a | Change the line item's inventory mode, or delete the cart |
| Stripe Checkout | 30 min to 24 h (session) | Stripe expires the session and sends `checkout.session.expired`; "your webhook handler can return to inventory any items reserved in the expired session" | n/a | The merchant's handler. Stripe: fulfilment "might be called multiple times, possibly concurrently", so handlers must tolerate repeats. |
| Magento MSI | none on reservations | `inventory_cleanup_reservations` (daily, `0 0 * * *`) deletes groups whose `SUM(quantity) = 0`, grouped by `metadata.object_id` / `object_increment_id` and `object_type`. `sales_clean_orders` (hourly) cancels `pending_payment` orders older than `delete_pending_after`, labelled "Pending Payment Order Lifetime (minutes)", default **480**. | Salable qty always sums the ledger | A compensating `+qty` row (`order_canceled`, `order_place_failed`, `shipment_created`, `creditmemo_created`). *Inference:* nothing structural stops a second compensation for the same event, unlike a delete by key. |
| Medusa | none | none | n/a | Compensation of `reserveInventoryStep` and order cancellation delete the `ReservationItem`s and adjust `reserved_quantity` |
| Shopify | "several minutes" | not described | not described | Claim on payment success. Details of release are not published. |

Sources:

- **Saleor:** [`settings.py`](https://github.com/saleor/saleor/blob/3.23.44/saleor/settings.py#L682-L685), [`warehouse/tasks.py`](https://github.com/saleor/saleor/blob/3.23.44/saleor/warehouse/tasks.py#L27)
- **Spree:** the [5.4→5.5 upgrade guide](https://github.com/spree/spree/blob/v5.6.1/docs/developer/upgrades/5.4-to-5.5.mdx), [`expire_job.rb`](https://github.com/spree/spree/blob/v5.6.1/spree/core/app/jobs/spree/stock_reservations/expire_job.rb), [`release.rb`](https://github.com/spree/spree/blob/v5.6.1/spree/core/app/services/spree/stock_reservations/release.rb)
- **WooCommerce:** [`wc-order-functions.php`](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/includes/wc-order-functions.php#L1091-L1158), [`wc-stock-functions.php`](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/includes/wc-stock-functions.php#L104-L127)
- **commercetools:** [inventory overview](https://docs.commercetools.com/api/inventory-overview)
- **Stripe:** [managing limited inventory](https://docs.stripe.com/payments/checkout/managing-limited-inventory), [fulfillment](https://docs.stripe.com/checkout/fulfillment)
- **Magento:**
  - inventory `1.2.9`: [`crontab.xml`](https://github.com/magento/inventory/blob/1.2.9/InventoryReservations/etc/crontab.xml), [`CleanupReservations.php`](https://github.com/magento/inventory/blob/1.2.9/InventoryReservations/Model/ResourceModel/CleanupReservations.php#L69-L80)
  - Magento `2.4.9`: [`CleanExpiredOrders.php`](https://github.com/magento/magento2/blob/2.4.9/app/code/Magento/Sales/Model/CronJob/CleanExpiredOrders.php), [`config.xml`](https://github.com/magento/magento2/blob/2.4.9/app/code/Magento/Sales/etc/config.xml), [`crontab.xml`](https://github.com/magento/magento2/blob/2.4.9/app/code/Magento/Sales/etc/crontab.xml)

Other first-party ways to make release happen only once:

- **Set semantics.** 배민 releases by `SREM` of the purchase number from a Set. Because of that, "재고사용량 차감에 대한 잘못된 구매번호의 이벤트가 발행되어도 재고사용량 차감에 영향을 미치지 않는다", so release could safely be **asynchronous** (an SQS event), while reserve stayed synchronous ([배민](https://techblog.woowahan.com/2709/)).
- **Workflow engines** demand it of compensations: "All compensations must be idempotent and able to handle cases where the forward Activity never executed." Temporal recommends registering the compensation *before* the activity runs, because "a credit card may be charged but the Activity fails before returning success" ([Temporal](https://docs.temporal.io/design-patterns/saga-pattern)).

## 5. Coupling to the payment outcome

### 5.1 Per system

**Saleor, legacy Payment API (`complete_checkout_with_payment`)** ([code](https://github.com/saleor/saleor/blob/3.23.44/saleor/checkout/complete_checkout.py#L1852-L2062)). This is the closest analogue to the assignment: three transactions.

1. Lock the checkout, validate, add the 45 s hold "without availability check", commit.
2. Lock the checkout and payment rows, call the gateway. `_fetch_gateway_response` turns a `GatewayError` or `PaymentError` into an unsuccessful transaction ([`gateway.py`](https://github.com/saleor/saleor/blob/3.23.44/saleor/payment/gateway.py#L513-L527)), `checkoutComplete` fails, and nothing is released explicitly: the 45 s hold lapses.
3. Re-lock the checkout and create the order. `allocate_stocks` re-checks availability against other checkouts' live holds. On `InsufficientStock` after a successful payment, `_complete_checkout_fail_handler` calls `gateway.payment_refund_or_void`.

So a **late success after the hold lapsed is refunded or voided automatically**. An unknown gateway result is a failure at that call.

**Saleor, Transaction API.** Payment apps record authorisations and charges on the *checkout*. `checkoutComplete` (manual, or a periodic auto-complete for fully authorised checkouts) creates the order. `InsufficientStock` there becomes a validation error, and the money stays on the checkout.

- Funds are released only if the channel opts in: `release_funds_for_expired_checkouts` (default `False`) after `checkout_ttl_before_releasing_funds` (default 6 h).
- "The release action is triggered only once", and "checkouts older than one year are never released".
- Order expiry (`expire_orders_after`, off by default) applies only to `UNCONFIRMED` orders with **no** `TransactionItem` and **no** `Payment`. An order with any payment record is never auto-expired.

Sources: [`channel/models.py`](https://github.com/saleor/saleor/blob/3.23.44/saleor/channel/models.py#L36-L73), [`payment/tasks.py`](https://github.com/saleor/saleor/blob/3.23.44/saleor/payment/tasks.py#L23-L85), [`order/tasks.py`](https://github.com/saleor/saleor/blob/3.23.44/saleor/order/tasks.py#L144-L171), [checkout lifecycle docs](https://docs.saleor.io/developer/checkout/lifecycle), [PaymentSettings reference](https://docs.saleor.io/api-reference/miscellaneous/objects/payment-settings).

**Medusa v2.** The workflow creates the order, reserves, then authorises.

- `AUTHORIZED` places the order.
- `PENDING_AUTHORIZATION`: "If the payment authorization is deferred (e.g., bank transfer, payment link), allow the workflow to proceed without a payment record." The order and its reservation exist, with no TTL. A later `authorized` webhook creates the payment.
- `REQUIRES_MORE` is a distinct error.
- **Anything else**, including a provider exception that is not a `MedusaError` (it is logged and swallowed, then the session status is read), raises `PAYMENT_AUTHORIZATION_ERROR`. Compensation then deletes the reservation and the order.

A webhook for a cart that has no order yet runs `completeCartWorkflow` again (`continueOnPermanentFailure: true`). If completion fails after money moved, "An authorized payment that wasn't captured is canceled" and "A captured payment is refunded, unless a later or concurrent completion attempt already placed an order for the same cart."

Sources: [`authorize-payment-session.ts`](https://github.com/medusajs/medusa/blob/v2.22.0/packages/core/core-flows/src/payment/steps/authorize-payment-session.ts#L74-L128), [`complete-cart.ts`](https://github.com/medusajs/medusa/blob/v2.22.0/packages/core/core-flows/src/cart/workflows/complete-cart.ts#L113-L130), [`process-payment.ts`](https://github.com/medusajs/medusa/blob/v2.22.0/packages/core/core-flows/src/payment/workflows/process-payment.ts#L226-L242), [webhook docs](https://docs.medusajs.com/resources/commerce-modules/payment/webhook-events).

**Magento MSI.** The `-qty` reservation is appended before the order is saved and before `Order::place` runs the payment method. If that throws (a payment `CommandException` or anything else), the plugin appends the compensation `order_place_failed` and rethrows ([plugin](https://github.com/magento/inventory/blob/1.2.9/InventorySales/Plugin/Sales/OrderManagement/AppendReservationsAfterOrderPlacementPlugin.php#L110-L184)).

For payment methods that leave the order in **Pending Payment**, Adobe's docs say the reservation stays in place. The order states are described as "Waiting for payment" and "Payment not received", and cancellation enters "Reservation compensation … to return partial or full quantity back to the stock salable quantity" ([order status and reservations](https://experienceleague.adobe.com/en/docs/commerce-admin/inventory/basics/order-status)). The 480-minute cron bounds this (section 4).

Mollie's own Magento plugin wiki describes the PSP side:

> "When the customer uses the browser Back button, the payment at Mollie is still pending, so the stock reservation remains active"

Stock is released only when Mollie's webhook reports canceled, expired or failed, and "For bank transfer payment,s this process can take much longer" ([Mollie wiki](https://github.com/mollie/magento2/wiki/Returning-the-stock-when-the-customer-abandons-the-payment)).

**Spree 5.5.** `Carts::Complete` runs inside `cart.with_lock` and does three things in order:

1. `process_payments!`
2. Advance to `complete`, which re-checks stock via the Quantifier, excluding the order's own holds.
3. `Release` the holds. The physical decrement happens in finalisation.

The payment webhook handler behaves as follows:

- `:failed` only fails the payment session; the reservation keeps running to its TTL.
- `:captured` and `:authorized` are idempotent ("if the session was already completed … skip duplicate processing") and call `Carts::Complete`.
- If the 10-minute hold lapsed and the stock was sold, completion fails and the handler reports the error through `Rails.error.report` and returns `failure`. I found no automatic refund in that path.

Sources: [`carts/complete.rb`](https://github.com/spree/spree/blob/v5.6.1/spree/core/app/services/spree/carts/complete.rb), [`handle_webhook.rb`](https://github.com/spree/spree/blob/v5.6.1/spree/core/app/services/spree/payments/handle_webhook.rb#L33-L54), [`order/checkout.rb`](https://github.com/spree/spree/blob/v5.6.1/spree/core/app/models/spree/order/checkout.rb#L74-L86).

**WooCommerce.** The hold is placed when checkout POSTs, before payment.

- **Success:** `payment_complete` reduces stock (once) and then deletes the hold.
- **`on-hold`** (for example, awaiting a bank transfer) *also* reduces stock and deletes the hold. A slow payment therefore holds a real decrement, not a TTL row.
- **`failed`, `cancelled` or `pending`** restore stock that was reduced.
- **Any exception** in the Store API checkout route frees the hold: "If we encountered an exception, free up stock and release held coupons."
- **Unpaid** checkout orders are cancelled after the hold window.
- **Late success:** `wc_reduce_stock_levels` decrements without checking availability, via the SQL `meta_value = meta_value - qty`. *Inference:* a payment that completes after the hold was released records an oversell rather than refusing it.

Sources: [`wc-stock-functions.php`](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/includes/wc-stock-functions.php#L104-L497), [Store API `Checkout.php`](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/src/StoreApi/Routes/V1/Checkout.php#L180-L188), [product data store](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/includes/data-stores/class-wc-product-data-store-cpt.php#L1855-L1897).

**Shopify.** The engineering post describes the checkout pair: reserve "When payment starts", claim "When payment succeeds".

For non-card methods still processing, the order status is **Pending**: "inventory is reserved for the order, but payment isn't guaranteed." With manual capture, the order status is **Authorized**: "The order is created, customers can complete their checkout, and inventory is reserved" ([order status](https://help.shopify.com/en/manual/fulfillment/managing-orders/order-status)).

Payment apps:

- A pending session takes a `pendingExpiresAt`, "recommended to be within 3 days".
- It must end with `paymentSessionResolve` or `paymentSessionReject`, and "Once you call either of these mutations, the payment state is final and can't be changed" ([paymentSessionPending](https://shopify.dev/docs/api/payments-apps/latest/mutations/paymentSessionPending)).

For offsite payment apps, "before finalizing the payment, the app must first call the paymentSessionConfirm mutation", because "Shopify needs to confirm that items are available before completing transactions". On a negative result the app must reject with `CONFIRMATION_REJECTED`; "This will happen if the products in the order are no longer available" ([payment processing](https://shopify.dev/docs/apps/build/payments/processing)).

**commercetools Checkout.** It creates a `Payment` with an `Initial` transaction and sends the authorisation to the PSP. "Since the authorization request is asynchronous … Checkout sets the `state` of the Transaction to `Pending` and creates an Order." With `ReserveOnOrder`, stock is therefore taken while payment is pending.

- If authorisation later fails: "it's your responsibility to decide how to proceed with the created Order."
- If payment succeeded but no order was created: "you can cancel the Payment or create the Order again."
- Checkout offers optional "Automated Reversal Predicates" to "automatically cancel or refund a Payment if Order creation fails."

Source: [payments lifecycle](https://docs.commercetools.com/checkout/payments-lifecycle).

**Stripe Checkout.** The inventory hold is the merchant's, and is released on `checkout.session.expired`. With delayed methods, Checkout *completes* before the money arrives: "Delayed payment methods generate a `checkout.session.async_payment_succeeded` event when payment succeeds later" ([fulfillment](https://docs.stripe.com/checkout/fulfillment)), and they "can take up to several days to process" ([PaymentIntent lifecycle](https://docs.stripe.com/payments/paymentintents/lifecycle)). *Inference:* a hold keyed to session expiry has to switch to "wait for the async event" once the session is complete but unpaid, because the session no longer expires.

**Toss Payments.** Payment is split into request, authentication and approval. The merchant must call `POST /v1/payments/confirm` while the authentication is valid: "결제 인증이 유효한 10분 안에 상점에서 결제 승인 API를 호출하지 않으면 해당 결제는 만료됩니다" ([API reference](https://docs.tosspayments.com/reference)).

- The integration guide repeats the 10 minutes for `NOT_FOUND_PAYMENT_SESSION`.
- The `EXPIRED` status description gives a different number: "결제 유효 시간 30분이 지나 거래가 취소된 상태입니다. IN_PROGRESS 상태에서 결제 승인 API를 호출하지 않으면 EXPIRED가 됩니다." The two numbers disagree in Toss's own docs.

Toss explains the split as data consistency. With combined request-and-approval, missing a webhook leaves the merchant's data inconsistent with Toss's ([payment flow](https://docs.tosspayments.com/guides/v2/get-started/payment-flow)).

- **After a timeout** the merchant may resend *the same* request: "네트워크 이슈나 타임아웃 문제로 응답을 받지 못했을 때도 안전하게 같은 요청을 다시 보낼 수 있습니다". The `Idempotency-Key` is valid for 15 days and accepted on every POST ([headers](https://docs.tosspayments.com/reference/using-api/authorization)).
- **Virtual accounts** sit in `WAITING_FOR_DEPOSIT`. `validHours` defaults to 168 (7 days) and can be at most 2,160 (90 days): "설정한 validHours 안에 구매자가 입금을 하지 않으면 주문은 취소됩니다."

**올리브영** ([Part 1](https://oliveyoung.tech/2022-04-20/Oliveyoung-Order-Payment-Process/)). The old flow approved payment at authentication and pushed the result asynchronously. When Olive Young failed to acknowledge it, "결제사에서 자체 망취소를 하게 됩니다", and the customer received a cancellation text.

The new flow splits authentication from approval and calls approval synchronously. "만약 응답이 오지 않는다면 일정시간 대기했다가 결제 실패 화면으로 전환한다." The post reports zero payment mismatches during the March 2022 sale, but does not say what happens to stock or to a PG approval whose response was lost. A batch verifies paid amounts after order completion ([Part 4](https://oliveyoung.tech/2024-06-25/oliveyoung-order-payment-part4/)).

**배민 선물하기** ([post](https://techblog.woowahan.com/2709/)). Stock usage is increased synchronously inside the purchase API. When a purchase is cancelled, "혹은 구매시 API 호출이 실패했을 경우", the purchase system publishes an asynchronous decrement event.

**Amazon.**

- "When you place an order, Amazon contacts the issuing bank … it isn't an actual charge."
- On shipment Amazon "notifies your bank that the order is concluded and the amount can be charged". A multi-item order is charged "after all items have shipped or five days after the order date, whichever occurs first" ([authorizations](https://www.amazon.com/gp/help/customer/display.html?nodeId=GCSEVDU4VHLPD4VX)).
- A declined payment can be retried from Your Orders: "If your payment is declined again, we'll cancel the order" ([declined payment](https://www.amazon.com/gp/help/customer/display.html?nodeId=G6P826S3WMEV8SPN)).
- Whether stock is held during that window is not documented.
- Buy with Prime: "If the order isn't successfully completed with payment confirmation after 10 minutes, the reserved inventory is released" ([Buy with Prime](https://buywithprime.amazon.com/knowledge-center/checkout)).

**Saga pattern.** The orchestrator creates an "`Order` in the `PENDING` state", sends "Reserve Credit", and approves or rejects on the reply ([microservices.io](https://microservices.io/patterns/data/saga.html)). In the Eventuate example, `CreateOrderSaga` defines `create` (compensated by `reject`) → `reserveCredit` → `approve`, with no timeout step ([`CreateOrderSaga.java`](https://github.com/eventuate-tram/eventuate-tram-sagas-examples-customers-and-orders/blob/894f2dbbe9aba2be6129748b6bbf5f30356deb07/order-service/order-service-sagas/src/main/java/io/eventuate/examples/tram/sagas/customersandorders/orders/sagas/CreateOrderSaga.java)). *Inference:* the order stays `PENDING` until a reply arrives, with delivery left to the messaging layer.

### 5.2 Answers to the three questions

**Does anyone keep the reservation while payment is pending?** Yes, but almost always as an order-level hold once the PSP has acknowledged the payment and it is waiting on a customer or bank:

- Magento `pending_payment` and Mollie pending
- Shopify Pending and Authorized
- commercetools Checkout's order created while `Pending`
- Medusa `pending_authorization`
- WooCommerce `on-hold`, which actually decrements stock

Each is bounded by an order-level deadline (Magento 480 min, WooCommerce 60 min for `pending`, Shopify recommends ≤ 3 days, Toss virtual accounts 7 days by default), with two exceptions:

- Medusa sets no expiry.
- Saleor never auto-expires an order that has any payment.

**Do they expire it anyway and accept an oversell or refund risk?** Short TTL holds expire regardless of payment state: Saleor's 45 s, Spree's 10 min, Buy with Prime's 10 min, Stripe sessions and commercetools carts. Where the engine also controls payment, it pairs the lapse with a late-completion path and a refund:

- Saleor legacy refunds or voids.
- Medusa cancels or refunds.
- Saleor's transaction flow releases funds after 6 h, opt-in.
- Spree reports the failure and leaves it to an operator.
- WooCommerce lets stock go negative.

**How is a late "payment succeeded" reconciled?** The patterns are automatic refund or void, optional automatic reversal (commercetools predicates), manual handling, or recording the oversell. Two systems **prevent** the case by keeping the irrevocable money step behind a stock check:

- Shopify's `paymentSessionConfirm` for offsite apps.
- The authenticate → confirm split (Toss's 10-minute window, 올리브영's synchronous approval), which lets a merchant re-check stock before it confirms.

**"Unknown" in the assignment's sense is rarer than "pending".** It means a lost or timed-out response to the create call. In the synchronous paths I read, engines treat it as *failure*: Saleor legacy, Medusa's non-pending error, WooCommerce's exception handler, and 올리브영's failure screen. They rely on the webhook plus refund path as the safety net. *Inference:* they can afford this because a late success can be refunded automatically, and some PSPs (Toss, Stripe) let a retry with the same idempotency key collapse the unknown.

## 6. Authorisation lifetime versus reservation lifetime

| Payment hold | Length | Source |
| --- | --- | --- |
| Stripe, online card, customer-initiated | 7 days for Visa, Mastercard, Amex and Discover; Visa merchant-initiated "4 days and 18 hours". `capture_before` on the charge says when. | [Stripe](https://docs.stripe.com/payments/place-a-hold-on-a-payment-method) |
| Stripe, other methods | Klarna until midnight of the 28th day; PayPal 10 + 10 days; Afterpay 13 days | same |
| Adyen | "Adyen expires (pre-)authorization requests automatically after 28 days". Mastercard final auth 7 days, pre-auth 30 days; Visa POS and MIT 5 days. | [Adyen](https://docs.adyen.com/online-payments/adjust-authorisation) |
| Shopify Payments | "an authorization period of 7 days". Capturing later costs "a 1.75% charge". An "Expiring" badge appears "two days before the deadline". | [Shopify authorization](https://help.shopify.com/en/manual/payments/payment-authorization), [order status](https://help.shopify.com/en/manual/fulfillment/managing-orders/order-status) |
| Toss card | Confirm within 10 min of authentication | [Toss reference](https://docs.tosspayments.com/reference) |

| Stock hold | Length |
| --- | --- |
| Saleor checkout / payment call | minutes (configurable) / 45 s |
| Spree, Buy with Prime | 10 min |
| WooCommerce | 60 min |
| Stripe Checkout session | 30 min to 24 h |
| commercetools `ReserveOnCart` | 1 min to 31 days |
| Order-level holds (Magento, Medusa, Saleor `Allocation`, Shopify `committed`, commercetools `ReserveOnOrder`) | until fulfilment or cancellation |

How systems line them up:

1. **Promote the hold at order placement.** The minutes-long hold only needs to cover the time until the order exists. After that the order-level hold has no TTL.
   - Saleor converts a reservation into an `Allocation` and deletes the checkout.
   - Spree releases the reservation at completion: "physical stock is decremented as before".
   - commercetools renews expired cart reservations during order creation.
2. **Capture at or before shipment, inside the authorisation window.**
   - Amazon charges at shipping, or on day 5.
   - Shopify warns two days ahead.
   - Stripe offers automatic delayed capture "about 6 hours before the expiration time" (private preview).
   - *Inference:* Amazon's five-day cap sits inside the 7-day card window.
3. **Keep the merchant confirm window shorter than the stock hold.** Toss allows 10 minutes and Saleor's payment-call hold is 45 s. *Inference:* this ordering means a confirm that succeeds normally lands while the stock is still held.
4. **Give asynchronous methods order-level holds with their own deadline,** not cart TTLs: Magento `pending_payment` (480 min), WooCommerce `on-hold`, Shopify `pendingExpiresAt` (≤ 3 days), Toss virtual accounts (`validHours`, default 168 h).

## 7. Comparison

| System | Reserve point | Data model | TTL | Release trigger | Unknown or pending payment |
| --- | --- | --- | --- | --- | --- |
| Saleor (legacy) | Checkout (opt-in) + 45 s during payment; `Allocation` at order | `Reservation` rows + `Stock.quantity_allocated` | minutes / 45 s / none | Lapse (lazy filter, daily delete); cancel deallocates | Gateway error = fail. Late success with stock gone → refund or void. |
| Saleor (transactions) | as above | as above | as above | as above | Money waits on the checkout. Opt-in release after 6 h. Orders with payments never auto-expire. |
| Medusa v2 | Order creation, before authorisation | `ReservationItem` + `reserved_quantity` | none | Compensation, cancel, fulfilment | Pending authorisation → order and hold kept, no expiry. Other errors → compensate (delete hold and order). Late webhook → re-complete or refund. |
| Magento MSI | Order placement, before payment | Append-only signed ledger | none (order cron 480 min) | Compensating row | Pending Payment keeps the hold until the PSP webhook or cron cancel. Exception during place → `order_place_failed` compensation. |
| Spree 5.5 | Checkout entry | Rows with `expires_at` | 10 min | Delete on complete or revert; expiry job | TTL runs regardless. Late webhook → complete or report error; no auto refund found. |
| Solidus 4.8 | none | `count_on_hand` | n/a | n/a | Availability check before payment, decrement after |
| WooCommerce | Checkout POST (order exists) | Rows with `expires` | 60 min | Delete on paid, on-hold, cancel or exception; unpaid cancel job | `pending` held for 60 min then cancelled. `on-hold` decrements stock. Exception → release. Late payment decrements without a floor. |
| Shopify | When payment starts; `committed` at order | One row per unit (pool); state quantities | "several minutes" | Claim on success | Pending order keeps inventory, ≤ 3 days recommended. Offsite apps must pass an inventory confirm before finalising. |
| commercetools | Per mode: cart (`ReserveOnCart`) or order | Platform `Reservation` + `InventoryEntry` | 1 min to 31 days | Mode change, cart delete, expiry | Checkout creates the Order while authorisation is Pending. Failure handling is the merchant's; optional auto-reversal. |
| Stripe Checkout (PSP) | merchant-defined | merchant-defined | session 30 min to 24 h | `checkout.session.expired` | Async methods complete first and succeed or fail days later |
| Toss (PSP) | merchant-defined | merchant-defined | confirm in 10 min; virtual account 7 days default | `EXPIRED` / virtual account cancel | Retry with the same `Idempotency-Key` (valid 15 days) |

## 8. Mapping to the assignment's model (*inference*, except where a source is cited)

The assignment: confirming a DRAFT order reserves stock in TX A; the PG call runs outside any transaction; TX B applies one verified outcome. CONFIRMED consumes the reservation, FAILED releases it exactly once, and UNKNOWN keeps it until reconciliation or a callback resolves it.

**Where it matches practice.**

- **Reserve, then call the PSP outside the stock lock, then apply.** This is Saleor's legacy checkout shape. Saleor states why it splits: "Process payments out of transaction to unlock stock rows for another user". Shopify's engineering post argues the same from connection pressure: long transactions near reservations made them unsafe "neighbors". The assignment is stricter than Spree and Magento, which call the gateway while holding a DB lock.
- **Consume in the same ACID step as the order change.** Shopify moved reservations into MySQL for exactly this: "Keep ACID guarantees between reservations and the inventory ledger". The assignment's TX B, which changes payment, order and reservation together ([ADR 0021](../adr/0021-payment-is-its-own-aggregate-and-slice.md) rule 4), is that design.
- **Release once.** Practice achieves "once" structurally: delete by order key (Spree, WooCommerce), a reduced flag (WooCommerce), set membership (배민), or a state guarded by a conditional update. The assignment's TX B changes the reservation only on `Changed(to = SUCCESS | FAILED)`, which is the same idea.
- **Keep the hold while the payment is undecided.** This matches how Magento, Shopify, commercetools, Medusa and WooCommerce treat *pending* payments.

**Where it differs.**

- **Every hold I found is bounded.** The bound is a TTL, an order-level expiry job, or a PSP-declared expiry (Toss's 10-minute confirm, Shopify's `pendingExpiresAt`, Mollie's expiry webhook). The only unbounded cases are Medusa's `pending_authorization` and Saleor's "orders with payments never expire". Both leave the ending to a merchant cancelling by hand, which is where [ADR 0022](../adr/0022-only-the-pre-create-rejection-proves-no-pg-transaction.md) also lands for zero-transaction UNKNOWN ("이 결제를 끝내는 것은 사람의 몫이다").
- **Engines usually treat a lost create or authorisation response as failure.** They can, because they own a late-success path: a webhook completes the order, or an automatic refund or void follows. The assignment refuses the conflation because a resend could double-charge and the test PG promises no merging of duplicate creates ([ADR 0022](../adr/0022-only-the-pre-create-rejection-proves-no-pg-transaction.md)). That is a defensible choice. It means the assignment pays in held stock what the engines pay in refunds.
- **Collapsing an unknown by idempotent retry.** Real PSPs offer it: Toss's `Idempotency-Key` (15 days, all POSTs) and Stripe's idempotency keys, which may be pruned after 24 h ([Stripe](https://docs.stripe.com/api/idempotent_requests)). The local test PG does not, which is exactly ADR 0022's first "다시 볼 조건".
- **No inventory confirm before money moves.** Shopify's offsite apps and Toss's authenticate → confirm split let the merchant check stock before the irrevocable step. In the assignment, the PG decides on its own after the create, so the merchant has no such step.

**Open questions this leaves.**

1. **Does UNKNOWN ever expire?** Industry bounds it with something the *PSP* declares, such as a confirm window, `pendingExpiresAt` or a virtual-account deadline. The test PG's equivalent would be a promise like "no transaction for this `pgOrderId` can appear after T", which is ADR 0022's second "다시 볼 조건". Without that promise, a commerce-side deadline must come with an answer to question 2.
2. **If a deadline does release stock, what does TX B do with a late CONFIRMED?** The options seen in practice are:
   - refund or void automatically (Saleor, Medusa), which needs a cancel API on the PG;
   - accept the oversell and record it (WooCommerce);
   - stop for an operator (Spree, and commercetools by default).
   Today a late CONFIRMED cannot meet a released reservation, because nothing releases on UNKNOWN.
3. **Should PENDING be treated differently from UNKNOWN?** PENDING means the PG acknowledged the transaction. Shopify gives pending payments a recommended bound of 3 days; Magento uses 480 minutes. UNKNOWN has no PG transaction known to exist. The two may deserve different bounds even if both keep the hold.
4. **How is "release exactly once" enforced in storage?** Possible shapes:
   - a reservation row keyed by order with a state (`RESERVED → CONSUMED | RELEASED`) moved by a conditional update;
   - a counter on `Product` guarded by the payment's own once-only transition.
   Every engine above uses rows keyed by order or line, plus a counter where reads need to be fast (Medusa, Saleor).
5. **Is availability for other buyers computed lazily or eagerly?** Lazily means `stock − Σ active reservations` at read time (Saleor, Spree, WooCommerce). Eagerly means decrementing a counter at reserve time (Medusa, Shopify's unit pool). With no TTL on the assignment's hold, both give the same answer. *Inference:* the choice matters only if question 1 adds an expiry.

## 9. Gaps and claims I could not verify

- **Shopify's pre-payment checkout behaviour.** I found no official doc saying whether carts or checkouts hold inventory before payment starts. The only primary statement is the engineering post's "When payment starts". Shopify Community threads contradict each other, so I dropped them.
- **commercetools.** The search index lists a 2026-06-04 "inventory reservations are now generally available" release note and a 2026-03-16 public-beta note. Both URLs returned 404 on 2026-10-22, so the GA date is unverified. The current inventory docs describe `ReserveOnCart` without a beta label.
- **Amazon.** WebFetch got HTTP 503 from `amazon.com` help pages; I read them through the Exa fetcher instead. Amazon documents no inventory hold during "payment revision needed".
- **Toss.** The confirm window is 10 minutes in the confirm API and in the `NOT_FOUND_PAYMENT_SESSION` troubleshooting text, but 30 minutes in the `EXPIRED` status description.
- **Coupang, Musinsa, Kurly, Zalando and Flipkart.** I found no first-party post on reservation tied to payment outcome. The Korean posts cited cover concurrency (Kurly, 올리브영 Part 3, 29CM) and payment flow (올리브영 Part 1), not reservation expiry. Several Korean and English results were personal blogs or system-design templates; I excluded them under the primary-source rule.
- **Two code paths I did not trace.** I did not check whether Saleor's legacy gateway plugins map timeouts to `PaymentError`, or how Saleor reconciles a gateway that charged but timed out. I also did not trace what WooCommerce's PayPal IPN handler does when a payment arrives for an order already cancelled for non-payment.
- **Magento's lock and the payment call.** That the `GET_LOCK` spans the payment call is an inference from the `placeOrder → submit → place → _placePayment` chain. Whether a gateway is called there depends on the payment method's action, for example authorise at place versus redirect.

## Sources

Code was read at the release tags below; the links are permalinks to those tags or commits. Docs and blogs were accessed on 2026-10-22.

**Open-source engines**

- Saleor `3.23.44`: [warehouse/models.py](https://github.com/saleor/saleor/blob/3.23.44/saleor/warehouse/models.py), [warehouse/reservations.py](https://github.com/saleor/saleor/blob/3.23.44/saleor/warehouse/reservations.py), [warehouse/lock_objects.py](https://github.com/saleor/saleor/blob/3.23.44/saleor/warehouse/lock_objects.py), [warehouse/management.py](https://github.com/saleor/saleor/blob/3.23.44/saleor/warehouse/management.py#L243-L265), [warehouse/tasks.py](https://github.com/saleor/saleor/blob/3.23.44/saleor/warehouse/tasks.py), [checkout/complete_checkout.py](https://github.com/saleor/saleor/blob/3.23.44/saleor/checkout/complete_checkout.py), [payment/gateway.py](https://github.com/saleor/saleor/blob/3.23.44/saleor/payment/gateway.py), [payment/tasks.py](https://github.com/saleor/saleor/blob/3.23.44/saleor/payment/tasks.py), [order/tasks.py](https://github.com/saleor/saleor/blob/3.23.44/saleor/order/tasks.py), [channel/models.py](https://github.com/saleor/saleor/blob/3.23.44/saleor/channel/models.py), [site/models.py](https://github.com/saleor/saleor/blob/3.23.44/saleor/site/models.py), [settings.py](https://github.com/saleor/saleor/blob/3.23.44/saleor/settings.py)
- Saleor docs (2026-10-22): [Stock reservation](https://docs.saleor.io/developer/stock/stock-reservation), [Checkout stock](https://docs.saleor.io/developer/checkout/stock), [Checkout lifecycle](https://docs.saleor.io/developer/checkout/lifecycle), [PaymentSettings](https://docs.saleor.io/api-reference/miscellaneous/objects/payment-settings)
- Medusa `v2.22.0`: [inventory models](https://github.com/medusajs/medusa/tree/v2.22.0/packages/modules/inventory/src/models), [inventory-module.ts](https://github.com/medusajs/medusa/blob/v2.22.0/packages/modules/inventory/src/services/inventory-module.ts), [complete-cart.ts](https://github.com/medusajs/medusa/blob/v2.22.0/packages/core/core-flows/src/cart/workflows/complete-cart.ts), [reserve-inventory.ts](https://github.com/medusajs/medusa/blob/v2.22.0/packages/core/core-flows/src/cart/steps/reserve-inventory.ts), [authorize-payment-session.ts](https://github.com/medusajs/medusa/blob/v2.22.0/packages/core/core-flows/src/payment/steps/authorize-payment-session.ts), [process-payment.ts](https://github.com/medusajs/medusa/blob/v2.22.0/packages/core/core-flows/src/payment/workflows/process-payment.ts), [cancel-order.ts](https://github.com/medusajs/medusa/blob/v2.22.0/packages/core/core-flows/src/order/workflows/cancel-order.ts), [locking providers](https://github.com/medusajs/medusa/blob/v2.22.0/packages/modules/locking/src/loaders/providers.ts)
- Medusa docs (2026-10-22): [Inventory concepts](https://docs.medusajs.com/resources/commerce-modules/inventory/concepts), [Inventory in flows](https://docs.medusajs.com/resources/commerce-modules/inventory/inventory-in-flows), [Payment webhook events](https://docs.medusajs.com/resources/commerce-modules/payment/webhook-events)
- Magento MSI `1.2.9`: [db_schema.xml](https://github.com/magento/inventory/blob/1.2.9/InventoryReservations/etc/db_schema.xml), [crontab.xml](https://github.com/magento/inventory/blob/1.2.9/InventoryReservations/etc/crontab.xml), [CleanupReservations.php](https://github.com/magento/inventory/blob/1.2.9/InventoryReservations/Model/ResourceModel/CleanupReservations.php), [AppendReservationsAfterOrderPlacementPlugin.php](https://github.com/magento/inventory/blob/1.2.9/InventorySales/Plugin/Sales/OrderManagement/AppendReservationsAfterOrderPlacementPlugin.php), [AppendReservations.php](https://github.com/magento/inventory/blob/1.2.9/InventorySales/Model/AppendReservations.php), [CartManagementPlugin.php](https://github.com/magento/inventory/blob/1.2.9/InventorySales/Plugin/Quote/CartManagementPlugin.php), [AcquireInventoryLock.php](https://github.com/magento/inventory/blob/1.2.9/InventorySales/Model/ResourceModel/AcquireInventoryLock.php), [CancelOrderItemObserver.php](https://github.com/magento/inventory/blob/1.2.9/InventorySales/Observer/CatalogInventory/CancelOrderItemObserver.php), [commit 40a2ac4](https://github.com/magento/inventory/commit/40a2ac4be9953c66b2839778e34e25c8539abb88)
- Magento MSI design doc: [Salable Quantity Calculation and Mechanism of Reservations](https://github.com/magento/inventory/wiki/Salable-Quantity-Calculation-and-Mechanism-of-Reservations) (wiki commit `39310ad`, accessed 2026-10-22)
- Magento `2.4.9`: [CleanExpiredOrders.php](https://github.com/magento/magento2/blob/2.4.9/app/code/Magento/Sales/Model/CronJob/CleanExpiredOrders.php), [Sales config.xml](https://github.com/magento/magento2/blob/2.4.9/app/code/Magento/Sales/etc/config.xml), [Sales crontab.xml](https://github.com/magento/magento2/blob/2.4.9/app/code/Magento/Sales/etc/crontab.xml), [Sales system.xml](https://github.com/magento/magento2/blob/2.4.9/app/code/Magento/Sales/etc/adminhtml/system.xml), [OrderService.php](https://github.com/magento/magento2/blob/2.4.9/app/code/Magento/Sales/Model/Service/OrderService.php), [QuoteManagement.php](https://github.com/magento/magento2/blob/2.4.9/app/code/Magento/Quote/Model/QuoteManagement.php), [Order.php](https://github.com/magento/magento2/blob/2.4.9/app/code/Magento/Sales/Model/Order.php)
- Adobe Commerce docs (2026-10-22): [Order status and reservations](https://experienceleague.adobe.com/en/docs/commerce-admin/inventory/basics/order-status), [Reservations](https://developer.adobe.com/commerce/php/development/framework/inventory-management/reservations)
- Mollie (PSP's own Magento plugin, 2026-10-22): [Returning the stock when the customer abandons the payment](https://github.com/mollie/magento2/wiki/Returning-the-stock-when-the-customer-abandons-the-payment)
- Spree `v5.6.1`: [migration](https://github.com/spree/spree/blob/v5.6.1/spree/core/db/migrate/20260501000001_create_spree_stock_reservations.rb), [stock_reservation.rb](https://github.com/spree/spree/blob/v5.6.1/spree/core/app/models/spree/stock_reservation.rb), [reserve.rb](https://github.com/spree/spree/blob/v5.6.1/spree/core/app/services/spree/stock_reservations/reserve.rb), [release.rb](https://github.com/spree/spree/blob/v5.6.1/spree/core/app/services/spree/stock_reservations/release.rb), [extend.rb](https://github.com/spree/spree/blob/v5.6.1/spree/core/app/services/spree/stock_reservations/extend.rb), [expire_job.rb](https://github.com/spree/spree/blob/v5.6.1/spree/core/app/jobs/spree/stock_reservations/expire_job.rb), [carts/update.rb](https://github.com/spree/spree/blob/v5.6.1/spree/core/app/services/spree/carts/update.rb), [carts/complete.rb](https://github.com/spree/spree/blob/v5.6.1/spree/core/app/services/spree/carts/complete.rb), [payments/handle_webhook.rb](https://github.com/spree/spree/blob/v5.6.1/spree/core/app/services/spree/payments/handle_webhook.rb), [order/checkout.rb](https://github.com/spree/spree/blob/v5.6.1/spree/core/app/models/spree/order/checkout.rb), [configuration.rb](https://github.com/spree/spree/blob/v5.6.1/spree/core/lib/spree/core/configuration.rb), [inventory.mdx](https://github.com/spree/spree/blob/v5.6.1/docs/developer/core-concepts/inventory.mdx), [5.4-to-5.5.mdx](https://github.com/spree/spree/blob/v5.6.1/docs/developer/upgrades/5.4-to-5.5.mdx)
- Solidus `v4.8.0`: [order state machine](https://github.com/solidusio/solidus/blob/v4.8.0/core/app/models/spree/core/state_machines/order/class_methods.rb), [order.rb](https://github.com/solidusio/solidus/blob/v4.8.0/core/app/models/spree/order.rb), [shipment.rb](https://github.com/solidusio/solidus/blob/v4.8.0/core/app/models/spree/shipment.rb), [stock_item.rb](https://github.com/solidusio/solidus/blob/v4.8.0/core/app/models/spree/stock_item.rb)
- WooCommerce `11.2.1`: [ReserveStock.php](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/src/Checkout/Helpers/ReserveStock.php), [wc-stock-functions.php](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/includes/wc-stock-functions.php), [wc-order-functions.php](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/includes/wc-order-functions.php), [class-wc-settings-products.php](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/includes/admin/settings/class-wc-settings-products.php), [class-wc-install.php](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/includes/class-wc-install.php), [class-wc-product-data-store-cpt.php](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/includes/data-stores/class-wc-product-data-store-cpt.php), [StoreApi Checkout.php](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/src/StoreApi/Routes/V1/Checkout.php), [stock-reservation.md](https://github.com/woocommerce/woocommerce/blob/11.2.1/plugins/woocommerce/client/blocks/docs/internal-developers/blocks/stock-reservation.md)

**SaaS platforms** (accessed 2026-10-22)

- Shopify: [We replaced Redis with MySQL for inventory reservations](https://shopify.engineering/scaling-inventory-reservations) (Emilie Noel, 2026-05-12), [Inventory states](https://help.shopify.com/en/manual/products/inventory/managing-inventory-quantities/inventory-states), [Apps in inventory management](https://shopify.dev/docs/apps/build/orders-fulfillment/inventory-management-apps), [Order status](https://help.shopify.com/en/manual/fulfillment/managing-orders/order-status), [Payment authorization](https://help.shopify.com/en/manual/payments/payment-authorization), [About payment processing](https://shopify.dev/docs/apps/build/payments/processing), [paymentSessionPending](https://shopify.dev/docs/api/payments-apps/latest/mutations/paymentSessionPending)
- commercetools: [Inventory overview](https://docs.commercetools.com/api/inventory-overview), [Reserve stock on Cart](https://docs.commercetools.com/tutorials/reserve-stock-on-cart), [Payments lifecycle (Checkout)](https://docs.commercetools.com/checkout/payments-lifecycle)
- Amazon: [Authorizations](https://www.amazon.com/gp/help/customer/display.html?nodeId=GCSEVDU4VHLPD4VX), [Shopping Cart Prices](https://www.amazon.com/gp/help/customer/display.html?nodeId=GF3GA88LAZ36ZMSQ), [Resolve a Declined Payment](https://www.amazon.com/gp/help/customer/display.html?nodeId=G6P826S3WMEV8SPN), [Buy with Prime checkout](https://buywithprime.amazon.com/knowledge-center/checkout)

**Payment providers** (accessed 2026-10-22)

- Stripe: [Manage limited inventory](https://docs.stripe.com/payments/checkout/managing-limited-inventory), [Create a Checkout Session (`expires_at`)](https://docs.stripe.com/api/checkout/sessions/create), [Fulfill orders](https://docs.stripe.com/checkout/fulfillment), [Place a hold on a payment method](https://docs.stripe.com/payments/place-a-hold-on-a-payment-method), [PaymentIntent lifecycle](https://docs.stripe.com/payments/paymentintents/lifecycle), [Idempotent requests](https://docs.stripe.com/api/idempotent_requests)
- Adyen: [Capture](https://docs.adyen.com/online-payments/capture), [Adjust authorisation (validity)](https://docs.adyen.com/online-payments/adjust-authorisation)
- Toss Payments: [API reference (confirm, Payment status, virtual account)](https://docs.tosspayments.com/reference), [Payment widget integration](https://docs.tosspayments.com/guides/v2/payment-widget/integration), [Payment flow](https://docs.tosspayments.com/guides/v2/get-started/payment-flow), [Headers and idempotency key](https://docs.tosspayments.com/reference/using-api/authorization), [Timeout glossary](https://docs.tosspayments.com/resources/glossary/timeout)

**First-party engineering blogs** (accessed 2026-10-22)

- 우아한형제들, [선물하기 시스템의 상품 재고는 어떻게 관리되어질까?](https://techblog.woowahan.com/2709/) (2021-01-19)
- 컬리, [신규 서비스 배포 전에 실험과 개선을 반복한 이야기](https://helloworld.kurly.com/blog/vsms-performance-experiment/) (2020-09-07)
- 올리브영, [결제 이야기 Part 1](https://oliveyoung.tech/2022-04-20/Oliveyoung-Order-Payment-Process/) (2022-04-20), [Part 3](https://oliveyoung.tech/2022-12-13/oliveyoung-transaction-orderstock/) (2022-12-13), [Part 4](https://oliveyoung.tech/2024-06-25/oliveyoung-order-payment-part4/) (2024-06-25)
- 29CM, [셀렉트샵에서 예약하기 서비스 개발기](https://medium.com/29cm/%EC%85%80%EB%A0%89%ED%8A%B8%EC%83%B5%EC%97%90%EC%84%9C-%EC%98%88%EC%95%BD%ED%95%98%EA%B8%B0-%EC%84%9C%EB%B9%84%EC%8A%A4-%EA%B0%9C%EB%B0%9C%EA%B8%B0-f7578ffcfbf7) (2022-09-22)

**Pattern sources**

- [microservices.io: Saga](https://microservices.io/patterns/data/saga.html) (accessed 2026-10-22)
- Eventuate Tram saga example at commit `894f2db`: [CreateOrderSaga.java](https://github.com/eventuate-tram/eventuate-tram-sagas-examples-customers-and-orders/blob/894f2dbbe9aba2be6129748b6bbf5f30356deb07/order-service/order-service-sagas/src/main/java/io/eventuate/examples/tram/sagas/customersandorders/orders/sagas/CreateOrderSaga.java), [OrderState.java](https://github.com/eventuate-tram/eventuate-tram-sagas-examples-customers-and-orders/blob/894f2dbbe9aba2be6129748b6bbf5f30356deb07/order-service/order-service-domain/src/main/java/io/eventuate/examples/tram/sagas/customersandorders/orders/domain/OrderState.java), [Customer.java](https://github.com/eventuate-tram/eventuate-tram-sagas-examples-customers-and-orders/blob/894f2dbbe9aba2be6129748b6bbf5f30356deb07/customer-service/customer-service-domain/src/main/java/io/eventuate/examples/tram/sagas/customersandorders/customers/domain/Customer.java)
- [Temporal: Saga pattern](https://docs.temporal.io/design-patterns/saga-pattern) (accessed 2026-10-22)
