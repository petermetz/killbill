/*
 * Copyright 2014-2018 Groupon, Inc
 * Copyright 2014-2018 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.beatrix.integration;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.StreamSupport;

import javax.inject.Inject;

import org.awaitility.Awaitility;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;
import org.killbill.billing.ErrorCode;
import org.killbill.billing.ObjectType;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.account.api.AccountData;
import org.killbill.billing.api.TestApiListener.NextEvent;
import org.killbill.billing.beatrix.util.InvoiceChecker.ExpectedInvoiceItemCheck;
import org.killbill.billing.catalog.api.BillingActionPolicy;
import org.killbill.billing.catalog.api.BillingPeriod;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.catalog.api.ProductCategory;
import org.killbill.billing.entitlement.api.DefaultEntitlement;
import org.killbill.billing.entitlement.api.Entitlement;
import org.killbill.billing.entitlement.api.Entitlement.EntitlementActionPolicy;
import org.killbill.billing.invoice.api.DryRunType;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.InvoiceApiException;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.api.InvoiceItemType;
import org.killbill.billing.invoice.model.ExternalChargeInvoiceItem;
import org.killbill.billing.invoice.model.ItemAdjInvoiceItem;
import org.killbill.billing.invoice.model.TaxInvoiceItem;
import org.killbill.billing.invoice.notification.DefaultNextBillingDateNotifier;
import org.killbill.billing.invoice.plugin.api.InvoiceContext;
import org.killbill.billing.invoice.plugin.api.InvoiceGroup;
import org.killbill.billing.invoice.plugin.api.InvoiceGroupingResult;
import org.killbill.billing.invoice.plugin.api.InvoicePluginApi;
import org.killbill.billing.invoice.plugin.api.InvoicePluginApiRetryException;
import org.killbill.billing.invoice.plugin.api.OnFailureInvoiceResult;
import org.killbill.billing.invoice.plugin.api.OnSuccessInvoiceResult;
import org.killbill.billing.invoice.plugin.api.PriorInvoiceResult;
import org.killbill.billing.osgi.api.OSGIServiceDescriptor;
import org.killbill.billing.osgi.api.OSGIServiceRegistration;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.platform.api.KillbillService.KILLBILL_SERVICES;
import org.killbill.billing.util.UUIDs;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.notificationq.api.NotificationEventWithMetadata;
import org.killbill.notificationq.api.NotificationQueue;
import org.killbill.notificationq.api.NotificationQueueService.NoSuchNotificationQueue;
import org.killbill.queue.retry.RetryNotificationEvent;
import org.killbill.queue.retry.RetryableService;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class TestWithInvoicePlugin extends TestIntegrationBase {

    @Inject
    private OSGIServiceRegistration<InvoicePluginApi> pluginRegistry;

    private TestInvoicePluginApi testInvoicePluginApi;

    @BeforeClass(groups = "slow")
    public void beforeClass() throws Exception {
        if (hasFailed()) {
            return;
        }

        super.beforeClass();

        this.testInvoicePluginApi = new TestInvoicePluginApi();
        pluginRegistry.registerService(new OSGIServiceDescriptor() {
            @Override
            public String getPluginSymbolicName() {
                return "TestInvoicePluginApi";
            }

            @Override
            public String getPluginName() {
                return "TestInvoicePluginApi";
            }

            @Override
            public String getRegistrationName() {
                return "TestInvoicePluginApi";
            }
        }, testInvoicePluginApi);
    }

    private static Function<Invoice, List<InvoiceItem>> NoTaxItems = new Function<Invoice, List<InvoiceItem>>() {
        @Override
        public List<InvoiceItem> apply(final Invoice invoice) {
            return null;
        }
    };

    private static Function<Invoice, List<InvoiceItem>> PerSubscriptionTaxItems = new Function<Invoice, List<InvoiceItem>>() {
        @Override
        public List<InvoiceItem> apply(final Invoice invoice) {
            final List<InvoiceItem> result = new ArrayList<>();
            invoice.getInvoiceItems()
                   .stream()
                   .forEach(ii -> {
                       if (ii.getSubscriptionId() != null) {
                           result.add(new TaxInvoiceItem(UUIDs.randomUUID(), invoice.getId(), invoice.getAccountId(), null, "Tax Item", ii.getStartDate(), BigDecimal.ONE, invoice.getCurrency(), ii.getId()));
                       }
                   });
            return result;
        }
    };

    private static Function<Invoice, InvoiceGroupingResult> NullInvoiceGroupingResult = new Function<Invoice, InvoiceGroupingResult>() {
        @Override
        public InvoiceGroupingResult apply(final Invoice invoice) {
            return null;
        }
    };


    private static Function<Iterable<PluginProperty>, Boolean> NoCheckPluginProperties = new Function<Iterable<PluginProperty>, Boolean>() {
        @Override
        public Boolean apply(final Iterable<PluginProperty> pluginProperties) {
            return Boolean.TRUE;
        }
    };


    @BeforeMethod(groups = "slow")
    public void setUp() throws Exception {
        if (hasFailed()) {
            return;
        }

        testInvoicePluginApi.additionalInvoiceItem = null;
        testInvoicePluginApi.isAborted = false;
        testInvoicePluginApi.shouldUpdateDescription = false;
        testInvoicePluginApi.rescheduleDate = null;
        testInvoicePluginApi.wasRescheduled = false;
        testInvoicePluginApi.priorCallInvocationCalls = 0;
        testInvoicePluginApi.onSuccessInvocationCalls = 0;
        testInvoicePluginApi.taxItems = NoTaxItems;
        testInvoicePluginApi.grpResult = NullInvoiceGroupingResult;
        testInvoicePluginApi.checkPluginProperties = NoCheckPluginProperties;

    }

    @Test(groups = "slow")
    public void testBasicAdditionalExternalChargeItem() throws Exception {

        testInvoicePluginApi.taxItems = PerSubscriptionTaxItems;

        // We take april as it has 30 days (easier to play with BCD)
        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDay(new LocalDate(2012, 4, 1));

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);
        accountChecker.checkAccount(account.getId(), accountData, callContext);

        final UUID pluginInvoiceItemId = UUID.randomUUID();
        final UUID pluginLinkedItemId = UUID.randomUUID();
        testInvoicePluginApi.additionalInvoiceItem = new ExternalChargeInvoiceItem(pluginInvoiceItemId,
                                                                                   clock.getUTCNow(),
                                                                                   null,
                                                                                   account.getId(),
                                                                                   null,
                                                                                   null,
                                                                                   null,
                                                                                   null,
                                                                                   null,
                                                                                   null,
                                                                                   null,
                                                                                   null,
                                                                                   "My charge",
                                                                                   clock.getUTCToday(),
                                                                                   null,
                                                                                   BigDecimal.TEN,
                                                                                   null,
                                                                                   Currency.USD,
                                                                                   pluginLinkedItemId,
                                                                                   null);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 0);

        // Create original subscription (Trial PHASE) -> $0 invoice but plugin added one item
        final DefaultEntitlement bpSubscription = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Pistol", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), 1, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.EXTERNAL_CHARGE, BigDecimal.TEN));
        subscriptionChecker.checkSubscriptionCreated(bpSubscription.getId(), internalCallContext);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 1);

        final List<Invoice> invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext);
        assertEquals(invoices.size(), 1);
        final List<InvoiceItem> invoiceItems = invoices.get(0).getInvoiceItems();
        final InvoiceItem externalCharge = Iterables.tryFind(invoiceItems, new Predicate<InvoiceItem>() {
            @Override
            public boolean apply(final InvoiceItem input) {
                return input.getInvoiceItemType() == InvoiceItemType.EXTERNAL_CHARGE;
            }
        }).orNull();
        assertNotNull(externalCharge);
        // verify the ID is the one passed by the plugin #818
        assertEquals(externalCharge.getId(), pluginInvoiceItemId);
        // verify the ID is the one passed by the plugin #887
        assertEquals(externalCharge.getLinkedItemId(), pluginLinkedItemId);

        // On next invoice we will update the amount and the description of the previously inserted EXTERNAL_CHARGE item
        testInvoicePluginApi.additionalInvoiceItem = new ExternalChargeInvoiceItem(pluginInvoiceItemId,
                                                                                   clock.getUTCNow(),
                                                                                   invoices.get(0).getId(),
                                                                                   account.getId(),
                                                                                   null,
                                                                                   null,
                                                                                   null,
                                                                                   null,
                                                                                   null,
                                                                                   null,
                                                                                   null,
                                                                                   null,
                                                                                   "Update Description",
                                                                                   clock.getUTCToday(),
                                                                                   null,
                                                                                   BigDecimal.ONE,
                                                                                   null,
                                                                                   Currency.USD,
                                                                                   pluginLinkedItemId,
                                                                                   null);

        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT);
        clock.addDays(30);
        assertListenerStatus();
        invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")));

        final List<Invoice> invoices2 = invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext);
        final List<InvoiceItem> invoiceItems2 = invoices2.get(0).getInvoiceItems();
        final InvoiceItem externalCharge2 = Iterables.tryFind(invoiceItems2, new Predicate<InvoiceItem>() {
            @Override
            public boolean apply(final InvoiceItem input) {
                return input.getInvoiceItemType() == InvoiceItemType.EXTERNAL_CHARGE;
            }
        }).orNull();
        assertNotNull(externalCharge2);

        assertEquals(externalCharge2.getAmount().compareTo(BigDecimal.ONE), 0);
    }

    @Test(groups = "slow")
    public void testBasicAdditionalItemAdjustment() throws Exception {

        testInvoicePluginApi.taxItems = PerSubscriptionTaxItems;

        // We take april as it has 30 days (easier to play with BCD)
        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDay(new LocalDate(2012, 4, 1));

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);
        accountChecker.checkAccount(account.getId(), accountData, callContext);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 0);

        // Create original subscription (Trial PHASE) -> $0 invoice.
        final DefaultEntitlement bpSubscription = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Pistol", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), 1, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.TAX, new BigDecimal("1.0")));
        subscriptionChecker.checkSubscriptionCreated(bpSubscription.getId(), internalCallContext);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 1);

        // Move to Evergreen PHASE
        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT);
        clock.addDays(30);
        assertListenerStatus();
        invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.TAX, new BigDecimal("1.0")));

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 2);

        final List<Invoice> invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext);
        assertEquals(invoices.size(), 2);
        final InvoiceItem recurringItem = Iterables.find(invoices.get(1).getInvoiceItems(),
                                                         new Predicate<InvoiceItem>() {
                                                             @Override
                                                             public boolean apply(final InvoiceItem input) {
                                                                 return InvoiceItemType.RECURRING.equals(input.getInvoiceItemType());
                                                             }
                                                         });

        // Item adjust the recurring item from the plugin
        final UUID pluginInvoiceItemId = UUID.randomUUID();
        final String itemDetails = "{\n" +
                                   "  \"user\": \"admin\",\n" +
                                   "  \"reason\": \"SLA not met\"\n" +
                                   "}";
        testInvoicePluginApi.additionalInvoiceItem = new ItemAdjInvoiceItem(pluginInvoiceItemId,
                                                                            clock.getUTCNow(),
                                                                            recurringItem.getInvoiceId(),
                                                                            account.getId(),
                                                                            clock.getUTCToday(),
                                                                            "My charge",
                                                                            BigDecimal.TEN.negate(),
                                                                            Currency.USD,
                                                                            recurringItem.getId(),
                                                                            itemDetails);

        // Move one month
        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.INVOICE_ADJUSTMENT, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT);
        clock.addMonths(1);
        assertListenerStatus();

        invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.TAX, new BigDecimal("1.0")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 5, 1), InvoiceItemType.ITEM_ADJ, BigDecimal.TEN.negate()),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 6, 1), InvoiceItemType.CBA_ADJ, BigDecimal.TEN));

        invoiceChecker.checkInvoice(account.getId(), 3, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 7, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 6, 1), InvoiceItemType.CBA_ADJ, BigDecimal.TEN.negate()));

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 3);

        final List<Invoice> refreshedInvoices = invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext);
        final List<InvoiceItem> invoiceItems = refreshedInvoices.get(1).getInvoiceItems();
        final InvoiceItem invoiceItemAdjustment = Iterables.tryFind(invoiceItems, new Predicate<InvoiceItem>() {
            @Override
            public boolean apply(final InvoiceItem input) {
                return input.getInvoiceItemType() == InvoiceItemType.ITEM_ADJ;
            }
        }).orNull();
        assertNotNull(invoiceItemAdjustment);
        // verify the ID is the one passed by the plugin #818
        assertEquals(invoiceItemAdjustment.getId(), pluginInvoiceItemId);
        // verify the details are passed by the plugin #888
        assertEquals(invoiceItemAdjustment.getItemDetails(), itemDetails);
    }

    @Test(groups = "slow")
    public void testAborted() throws Exception {

        // We take april as it has 30 days (easier to play with BCD)
        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDay(new LocalDate(2012, 4, 1));

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);
        accountChecker.checkAccount(account.getId(), accountData, callContext);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 0);

        // Create original subscription (Trial PHASE) -> $0 invoice
        final DefaultEntitlement bpSubscription = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Pistol", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        invoiceChecker.checkInvoice(account.getId(), 1, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        subscriptionChecker.checkSubscriptionCreated(bpSubscription.getId(), internalCallContext);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 1);

        // Abort invoice runs
        testInvoicePluginApi.isAborted = true;

        // Move to Evergreen PHASE
        busHandler.pushExpectedEvents(NextEvent.PHASE);
        clock.addDays(30);
        assertListenerStatus();
        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext).size(), 1);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 2);

        // No notification, so by default, the account will not be re-invoiced
        clock.addMonths(1);
        assertListenerStatus();
        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext).size(), 1);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 2);

        // No notification, so by default, the account will not be re-invoiced
        clock.addMonths(1);
        assertListenerStatus();
        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext).size(), 1);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 2);

        // Re-enable invoicing
        testInvoicePluginApi.isAborted = false;

        // Trigger a manual invoice run
        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT);
        invoiceUserApi.triggerInvoiceGeneration(account.getId(), clock.getUTCToday(), Collections.emptyList(), callContext);
        assertListenerStatus();
        invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 7, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 1), new LocalDate(2012, 8, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")));

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 3);

        // Invoicing resumes
        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT);
        clock.addMonths(1);
        assertListenerStatus();
        invoiceChecker.checkInvoice(account.getId(), 3, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 8, 1), new LocalDate(2012, 9, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")));

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 4);
    }

    @Test(groups = "slow")
    public void testUpdateDescription() throws Exception {

        testInvoicePluginApi.shouldUpdateDescription = true;

        // We take april as it has 30 days (easier to play with BCD)
        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDay(new LocalDate(2012, 4, 1));

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);
        accountChecker.checkAccount(account.getId(), accountData, callContext);

        // Create original subscription (Trial PHASE) -> $0 invoice but plugin added one item
        final Entitlement bpSubscription = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Pistol", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        final Invoice firstInvoice = invoiceChecker.checkInvoice(account.getId(), 1, callContext,
                                                                 new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        subscriptionChecker.checkSubscriptionCreated(bpSubscription.getId(), internalCallContext);
        checkInvoiceDescriptions(firstInvoice);

        // Move to Evergreen PHASE
        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT);
        clock.addDays(30);
        assertListenerStatus();
        final Invoice secondInvoice = invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                                                  new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")));
        checkInvoiceDescriptions(secondInvoice);

        // Cancel START_OF_TERM to make sure odd items like CBA are updated too
        busHandler.pushExpectedEvents(NextEvent.BLOCK, NextEvent.CANCEL, NextEvent.INVOICE);
        bpSubscription.cancelEntitlementWithPolicyOverrideBillingPolicy(EntitlementActionPolicy.IMMEDIATE,
                                                                        BillingActionPolicy.START_OF_TERM,
                                                                        ImmutableList.<PluginProperty>of(),
                                                                        callContext);
        assertListenerStatus();
        final Invoice thirdInvoice = invoiceChecker.checkInvoice(account.getId(), 3, callContext,
                                                                 new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("29.95").negate()),
                                                                 new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 5, 1), InvoiceItemType.CBA_ADJ, new BigDecimal("29.95")));
        checkInvoiceDescriptions(thirdInvoice);
    }

    @Test(groups = "slow", description = "See https://github.com/killbill/killbill/issues/1316")
    public void testDryrunWithModifiedUsageItem() throws Exception {

        testInvoicePluginApi.shouldUpdateDescription = true;

        clock.setTime(new DateTime(2017, 6, 16, 18, 24, 42, 0));

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);
        accountChecker.checkAccount(account.getId(), accountData, callContext);

        add_AUTO_INVOICING_DRAFT_Tag(account.getId(), ObjectType.ACCOUNT);
        add_AUTO_INVOICING_REUSE_DRAFT_Tag(account.getId(), ObjectType.ACCOUNT);

        final DefaultEntitlement bpEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", "Shotgun", ProductCategory.BASE, BillingPeriod.ANNUAL, NextEvent.CREATE, NextEvent.BLOCK);
        assertNotNull(bpEntitlement);

        assertNull(subscriptionApi.getSubscriptionForEntitlementId(bpEntitlement.getBaseEntitlementId(), callContext).getChargedThroughDate());

        //
        // ADD ADD_ON ON THE SAME DAY
        //
        final DefaultEntitlement aoSubscription = addAOEntitlementAndCheckForCompletion(bpEntitlement.getBundleId(), "Bullets", ProductCategory.ADD_ON, BillingPeriod.NO_BILLING_PERIOD, NextEvent.CREATE, NextEvent.BLOCK);

        recordUsageData(aoSubscription.getId(), "tracking-1", "bullets", new LocalDate(2017, 6, 18), BigDecimal.valueOf(99L), callContext);
        recordUsageData(aoSubscription.getId(), "tracking-2", "bullets", new LocalDate(2017, 7, 25), BigDecimal.valueOf(100L), callContext);

        recordUsageData(aoSubscription.getId(), "tracking-3", "bullets", new LocalDate(2017, 8, 18), BigDecimal.valueOf(99L), callContext);
        recordUsageData(aoSubscription.getId(), "tracking-4", "bullets", new LocalDate(2017, 9, 25), BigDecimal.valueOf(100L), callContext);

        final Invoice dryRunInvoice = invoiceUserApi.triggerDryRunInvoiceGeneration(account.getId(), new LocalDate(2018, 8, 15), new TestDryRunArguments(DryRunType.TARGET_DATE), Collections.emptyList(), callContext);
        assertEquals(dryRunInvoice.getInvoiceItems().size(), 16);

    }

    private void checkInvoiceDescriptions(final Invoice invoice) {
        for (final InvoiceItem invoiceItem : invoice.getInvoiceItems()) {
            if (invoiceItem.getInvoiceItemType() == InvoiceItemType.CBA_ADJ) {
                // CBA_ABJ will be recomputed and recreated after we come back from the plugin
                continue;
            }
            assertEquals(invoiceItem.getDescription(), String.format("[plugin] %s", invoiceItem.getId()));
        }
    }

    @Test(groups = "slow")
    public void testRescheduledViaNotification() throws Exception {

        // We take april as it has 30 days (easier to play with BCD)
        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDay(new LocalDate(2012, 4, 1));

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);
        accountChecker.checkAccount(account.getId(), accountData, callContext);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 0);

        // Create original subscription (Trial PHASE) -> $0 invoice
        final DefaultEntitlement bpSubscription = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Pistol", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        invoiceChecker.checkInvoice(account.getId(), 1, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        subscriptionChecker.checkSubscriptionCreated(bpSubscription.getId(), internalCallContext);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 1);
        Assert.assertFalse(testInvoicePluginApi.wasRescheduled);

        // Reschedule invoice generation
        final DateTime utcNow = clock.getUTCNow();
        testInvoicePluginApi.rescheduleDate = new DateTime(2012, 5, 2, utcNow.getHourOfDay(), utcNow.getMinuteOfHour(), utcNow.getSecondOfMinute(), DateTimeZone.UTC);

        // Move to Evergreen PHASE
        busHandler.pushExpectedEvents(NextEvent.PHASE);
        clock.addDays(30);
        assertListenerStatus();
        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext).size(), 1);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 2);
        Assert.assertFalse(testInvoicePluginApi.wasRescheduled);

        // PHASE invoice has been rescheduled, reset rescheduleDate
        testInvoicePluginApi.rescheduleDate = null;

        // Move one day
        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT);
        clock.addDays(1);
        assertListenerStatus();
        invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")));

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 3);
        Assert.assertTrue(testInvoicePluginApi.wasRescheduled);

        // Invoicing resumes as expected
        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT);
        clock.addDays(30);
        assertListenerStatus();
        invoiceChecker.checkInvoice(account.getId(), 3, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 7, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")));

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 4);
        Assert.assertFalse(testInvoicePluginApi.wasRescheduled);
    }

    @Test(groups = "slow")
    public void testRescheduledViaAPI() throws Exception {

        // We take april as it has 30 days (easier to play with BCD)
        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDay(new LocalDate(2012, 4, 1));

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);
        accountChecker.checkAccount(account.getId(), accountData, callContext);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 0);

        // Create original subscription (Trial PHASE) -> $0 invoice
        final DefaultEntitlement bpSubscription = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Pistol", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        invoiceChecker.checkInvoice(account.getId(), 1, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        subscriptionChecker.checkSubscriptionCreated(bpSubscription.getId(), internalCallContext);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 1);

        // Reschedule invoice generation at the time of the PHASE event
        testInvoicePluginApi.rescheduleDate = new DateTime(clock.getUTCNow()).plusDays(30);

        try {
            invoiceUserApi.triggerInvoiceGeneration(account.getId(), clock.getUTCToday(), Collections.emptyList(), callContext);
            Assert.fail();
        } catch (final InvoiceApiException e) {
            Assert.assertEquals(e.getCode(), ErrorCode.INVOICE_NOTHING_TO_DO.getCode());
        }
        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 2);
        Assert.assertFalse(testInvoicePluginApi.wasRescheduled);

        // Let the next invoice go through
        testInvoicePluginApi.rescheduleDate = null;

        // Move to Evergreen PHASE: two invoice runs will be triggers, one by SubscriptionNotificationKey (PHASE event) and one by NextBillingDateNotificationKey (reschedule)
        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE, NextEvent.NULL_INVOICE, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT);
        clock.addDays(30);
        assertListenerStatus();
        invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")));

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 4);
        // Cannot check wasRescheduled flag, as it would be true only for one of the runs

        // Reschedule next invoice one month in the future
        testInvoicePluginApi.rescheduleDate = clock.getUTCNow().plusMonths(1);
        try {
            invoiceUserApi.triggerInvoiceGeneration(account.getId(), clock.getUTCToday(), Collections.emptyList(), callContext);
            Assert.fail();
        } catch (final InvoiceApiException e) {
            Assert.assertEquals(e.getCode(), ErrorCode.INVOICE_NOTHING_TO_DO.getCode());
        }
        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 5);
        Assert.assertFalse(testInvoicePluginApi.wasRescheduled);

        // Let the next invoice go through
        testInvoicePluginApi.rescheduleDate = null;

        // Move one month ahead: no NULL_INVOICE this time: since there is already a notification for that date, the reschedule is a no-op (and we keep the isRescheduled flag to false)
        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT);
        clock.addMonths(1);
        assertListenerStatus();
        invoiceChecker.checkInvoice(account.getId(), 3, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 7, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")));

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 6);
        Assert.assertFalse(testInvoicePluginApi.wasRescheduled);
    }

    @Test(groups = "slow")
    public void testWithRetries() throws Exception {

        testInvoicePluginApi.taxItems = PerSubscriptionTaxItems;

        // We take april as it has 30 days (easier to play with BCD)
        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDay(new LocalDate(2012, 4, 1));

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);
        accountChecker.checkAccount(account.getId(), accountData, callContext);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 0);

        // Make invoice plugin fail
        testInvoicePluginApi.shouldThrowException = true;

        // Create original subscription (Trial PHASE)
        final DefaultEntitlement bpSubscription = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Pistol", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK);
        subscriptionChecker.checkSubscriptionCreated(bpSubscription.getId(), internalCallContext);
        // Invoice failed to generate
        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext).size(), 0);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 1);

        // Verify bus event has moved to the retry service (can't easily check the timestamp unfortunately)
        // No future notification at this point (FIXED item, the PHASE event is the trigger for the next one)
        checkRetryBusEvents(1, 0);

        // Add 5'
        clock.addDeltaFromReality(5 * 60 * 1000);
        checkRetryBusEvents(2, 0);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 2);

        // Fix invoice plugin
        testInvoicePluginApi.shouldThrowException = false;

        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT);
        clock.addDeltaFromReality(10 * 60 * 1000);
        assertListenerStatus();
        // No notification in the main queue at this point (the PHASE event is the trigger for the next one)
        checkNotificationsNoRetry(0);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 3);

        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext).size(), 1);
        invoiceChecker.checkInvoice(account.getId(),
                                    1,
                                    callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, BigDecimal.ZERO),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.TAX, new BigDecimal("1.0")));

        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT);
        clock.setDay(new LocalDate("2012-05-01"));
        assertListenerStatus();
        checkNotificationsNoRetry(1);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 4);

        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext).size(), 2);
        invoiceChecker.checkInvoice(account.getId(),
                                    2,
                                    callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), null, InvoiceItemType.TAX, new BigDecimal("1.0")));

        // Make invoice plugin fail again
        testInvoicePluginApi.shouldThrowException = true;

        clock.addMonths(1);
        assertListenerStatus();

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 5);

        // Invoice failed to generate
        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext).size(), 2);

        // Verify notification has moved to the retry service
        checkRetryNotifications("2012-06-01T00:05:00", 1);

        // Add 5'
        clock.addDeltaFromReality(5 * 60 * 1000);
        // Verify there are no notification duplicates
        checkRetryNotifications("2012-06-01T00:15:00", 1);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 6);

        // Fix invoice plugin
        testInvoicePluginApi.shouldThrowException = false;

        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT);
        clock.addDeltaFromReality(10 * 60 * 1000);
        assertListenerStatus();
        checkNotificationsNoRetry(1);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 7);

        // Invoice was generated
        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext).size(), 3);
        invoiceChecker.checkInvoice(account.getId(),
                                    3,
                                    callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 7, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), null, InvoiceItemType.TAX, new BigDecimal("1.0")));

        // Make invoice plugin fail again
        testInvoicePluginApi.shouldThrowException = true;

        clock.setTime(new DateTime("2012-07-01T00:00:00"));
        assertListenerStatus();

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 8);

        // Invoice failed to generate
        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext).size(), 3);

        // Verify notification has moved to the retry service
        checkRetryNotifications("2012-07-01T00:05:00", 1);

        testInvoicePluginApi.shouldThrowException = false;

        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        clock.addDeltaFromReality(5 * 60 * 1000);
        assertListenerStatus();
        checkNotificationsNoRetry(1);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 9);

        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext).size(), 4);
    }


    @Test(groups = "slow")
    public void testValidatePluginProperties() throws Exception {


        // We take april as it has 30 days (easier to play with BCD)
        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDay(new LocalDate(2012, 4, 1));

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);
        accountChecker.checkAccount(account.getId(), accountData, callContext);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 0);

        // Create original subscription (Trial PHASE) -> $0 invoice
        final DefaultEntitlement bpSubscription = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Pistol", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        invoiceChecker.checkInvoice(account.getId(), 1, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        subscriptionChecker.checkSubscriptionCreated(bpSubscription.getId(), internalCallContext);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 1);

        // Abort invoice runs
        testInvoicePluginApi.isAborted = true;

        // Move to Evergreen PHASE
        busHandler.pushExpectedEvents(NextEvent.PHASE);
        clock.addDays(30);
        assertListenerStatus();
        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext).size(), 1);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 2);

        // No notification, so by default, the account will not be re-invoiced
        clock.addMonths(1);
        assertListenerStatus();
        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext).size(), 1);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 2);

        // No notification, so by default, the account will not be re-invoiced
        clock.addMonths(1);
        assertListenerStatus();
        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext).size(), 1);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 2);

        // Re-enable invoicing
        testInvoicePluginApi.isAborted = false;

        final List<PluginProperty> expectedProperties = new ArrayList<>();
        expectedProperties.add(new PluginProperty("tree", "leaf", true));
        expectedProperties.add(new PluginProperty("shirt", "button", true));
        expectedProperties.add(new PluginProperty("soccer", "ball", true));
        testInvoicePluginApi.checkPluginProperties = new Function<Iterable<PluginProperty>, Boolean>() {
            @Override
            public Boolean apply(final Iterable<PluginProperty> pluginProperties) {
                final BigInteger found = StreamSupport.stream(pluginProperties.spliterator(), false)
                             .filter(p -> expectedProperties.contains(p))
                             .map(p -> BigInteger.ONE)
                             .reduce(BigInteger.ZERO, BigInteger::add);
                return found.intValue() == expectedProperties.size();
            }
        };

        // Trigger a manual invoice run
        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT);
        invoiceUserApi.triggerInvoiceGeneration(account.getId(), clock.getUTCToday(), expectedProperties, callContext);
        assertListenerStatus();
        invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 7, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 1), new LocalDate(2012, 8, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")));

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 3);

    }

    @Test(groups = "slow")
    public void testInvoiceGrouping() throws Exception {

        // We take april as it has 30 days (easier to play with BCD)
        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDay(new LocalDate(2012, 4, 1));

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);
        accountChecker.checkAccount(account.getId(), accountData, callContext);

        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 0);

        // Create original subscription (Trial PHASE) -> $0 invoice but plugin added one item
        final DefaultEntitlement sub1 = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey1", "Pistol", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        invoiceChecker.checkInvoice(account.getId(), 1, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        subscriptionChecker.checkSubscriptionCreated(sub1.getId(), internalCallContext);
        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 1);

        final DefaultEntitlement sub2 = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey2", "Pistol", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        subscriptionChecker.checkSubscriptionCreated(sub2.getId(), internalCallContext);
        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 2);

        final DefaultEntitlement sub3 = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey3", "Pistol", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        invoiceChecker.checkInvoice(account.getId(), 3, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        subscriptionChecker.checkSubscriptionCreated(sub3.getId(), internalCallContext);
        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 3);

        testInvoicePluginApi.grpResult = new Function<Invoice, InvoiceGroupingResult>() {

            private UUID findGroup(Invoice inv, InvoiceItem item) {
                if (item.getSubscriptionId() != null) {
                    return item.getSubscriptionId();
                } else if (item.getLinkedItemId() != null) {
                    final InvoiceItem target = inv.getInvoiceItems()
                                                  .stream()
                                                  .filter(ii -> ii.getId().equals(item.getLinkedItemId()))
                                                  .findAny()
                                                  .orElseThrow();
                    return target.getSubscriptionId();
                } else {
                    throw new IllegalStateException("Unexpected item not related to subscription ii=" + item);
                }
            }

            @Override
            public InvoiceGroupingResult apply(final Invoice invoice) {
                final Map<UUID, List<UUID>> groups = new HashMap<UUID, List<UUID>>();
                for (InvoiceItem ii : invoice.getInvoiceItems()) {
                    final UUID groupId = findGroup(invoice, ii);
                    if (groups.get(groupId) == null) {
                        groups.put(groupId, new ArrayList<>());
                    }
                    groups.get(groupId).add(ii.getId());
                }
                return new TestInvoiceGroupingResult(groups);
            }
        };

        testInvoicePluginApi.taxItems = PerSubscriptionTaxItems;

        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.PHASE, NextEvent.PHASE, /* 1 for each subscription */
                                      NextEvent.INVOICE, NextEvent.INVOICE, NextEvent.INVOICE, NextEvent.NULL_INVOICE, NextEvent.NULL_INVOICE,  /* 3 split invoices for 1st phase + 2 null invoices for remaining 2 subs */
                                      NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT, NextEvent.PAYMENT /* 1 payment for each invoice */);
        clock.addMonths(1);
        assertListenerStatus();
        Assert.assertEquals(testInvoicePluginApi.priorCallInvocationCalls, 6);
        // On success was called for each split invoice.
        Assert.assertEquals(testInvoicePluginApi.onSuccessInvocationCalls, 8);

        final List<Invoice> invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext);
        assertEquals(invoices.size(), 6);

        // Verify we have indeed 3 split invoices for the targetDate 20122-05-1 and each having one RECURRING + its mathcing TAX item
        long splitInvoices = invoices.stream()
                                     .filter(i -> {
                                         if (!i.getTargetDate().equals(new LocalDate(2012, 5, 1)) ||
                                             i.getInvoiceItems().size() != 2) {
                                             return false;
                                         }

                                         final InvoiceItem first = i.getInvoiceItems().get(0);
                                         final InvoiceItem second = i.getInvoiceItems().get(1);

                                         if ((first.getInvoiceItemType() == InvoiceItemType.RECURRING &&
                                              second.getInvoiceItemType() == InvoiceItemType.TAX &&
                                              second.getLinkedItemId().equals(first.getId())) ||
                                             (first.getInvoiceItemType() == InvoiceItemType.TAX &&
                                              second.getInvoiceItemType() == InvoiceItemType.RECURRING &&
                                              first.getLinkedItemId().equals(second.getId()))) {
                                             return true;
                                         }
                                         return false;
                                     })
                                     .count();
        Assert.assertEquals(splitInvoices, 3);
    }

    private static class TestInvoiceGroupingResult implements InvoiceGroupingResult {

        private List<InvoiceGroup> invoiceGroups;

        public TestInvoiceGroupingResult(final Map<UUID, List<UUID>> groups) {
            this.invoiceGroups = initGroups(groups);
        }

        private List<InvoiceGroup> initGroups(final Map<UUID, List<UUID>> groups) {
            final List<InvoiceGroup> tmp = new ArrayList<>();
            groups.values()
                  .stream()
                  .forEach(v -> tmp.add(new TestInvoiceGroup(v)));
            return tmp;
        }

        @Override
        public List<InvoiceGroup> getInvoiceGroups() {
            return invoiceGroups;
        }

        private static class TestInvoiceGroup implements InvoiceGroup {

            private final List<UUID> invoiceItemIds;

            public TestInvoiceGroup(final List<UUID> invoiceItemIds) {
                this.invoiceItemIds = invoiceItemIds;
            }

            @Override
            public List<UUID> getInvoiceItemIds() {
                return invoiceItemIds;
            }
        }
    }

    private void checkRetryBusEvents(final int retryNb, final int expectedFutureInvoiceNotifications) throws NoSuchNotificationQueue {
        // Verify notification(s) moved to the retry queue
        Awaitility.await().atMost(15, TimeUnit.SECONDS).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                final List<NotificationEventWithMetadata> futureInvoiceRetryableBusEvents = getFutureInvoiceRetryableBusEvents();
                return futureInvoiceRetryableBusEvents.size() == 1 && ((RetryNotificationEvent) futureInvoiceRetryableBusEvents.get(0).getEvent()).getRetryNb() == retryNb;
            }
        });
        assertEquals(getFutureInvoiceNotifications().size(), expectedFutureInvoiceNotifications);
    }

    private void checkRetryNotifications(final String retryDateTime, final int expectedFutureInvoiceNotifications) throws NoSuchNotificationQueue {
        // Verify notification(s) moved to the retry queue
        Awaitility.await().atMost(15, TimeUnit.SECONDS).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                final List<NotificationEventWithMetadata> futureInvoiceRetryableNotifications = getFutureInvoiceRetryableNotifications();
                return futureInvoiceRetryableNotifications.size() == 1 && futureInvoiceRetryableNotifications.get(0).getEffectiveDate().compareTo(new DateTime(retryDateTime, DateTimeZone.UTC)) == 0;
            }
        });
        assertEquals(getFutureInvoiceNotifications().size(), expectedFutureInvoiceNotifications);
    }

    private void checkNotificationsNoRetry(final int main) throws NoSuchNotificationQueue {
        assertEquals(getFutureInvoiceRetryableNotifications().size(), 0);
        assertEquals(getFutureInvoiceNotifications().size(), main);
    }

    private List<NotificationEventWithMetadata> getFutureInvoiceNotifications() throws NoSuchNotificationQueue {
        final NotificationQueue notificationQueue = notificationQueueService.getNotificationQueue(KILLBILL_SERVICES.INVOICE_SERVICE.getServiceName(), DefaultNextBillingDateNotifier.NEXT_BILLING_DATE_NOTIFIER_QUEUE);
        return ImmutableList.<NotificationEventWithMetadata>copyOf(notificationQueue.getFutureNotificationForSearchKeys(internalCallContext.getAccountRecordId(), internalCallContext.getTenantRecordId()));
    }

    private List<NotificationEventWithMetadata> getFutureInvoiceRetryableNotifications() throws NoSuchNotificationQueue {
        final NotificationQueue notificationQueue = notificationQueueService.getNotificationQueue(RetryableService.RETRYABLE_SERVICE_NAME, DefaultNextBillingDateNotifier.NEXT_BILLING_DATE_NOTIFIER_QUEUE);
        return ImmutableList.<NotificationEventWithMetadata>copyOf(notificationQueue.getFutureNotificationForSearchKeys(internalCallContext.getAccountRecordId(), internalCallContext.getTenantRecordId()));
    }

    private List<NotificationEventWithMetadata> getFutureInvoiceRetryableBusEvents() throws NoSuchNotificationQueue {
        final NotificationQueue notificationQueue = notificationQueueService.getNotificationQueue(RetryableService.RETRYABLE_SERVICE_NAME, "invoice-listener");
        return ImmutableList.<NotificationEventWithMetadata>copyOf(notificationQueue.getFutureNotificationForSearchKeys(internalCallContext.getAccountRecordId(), internalCallContext.getTenantRecordId()));
    }

    public class TestInvoicePluginApi implements InvoicePluginApi {

        boolean shouldThrowException = false;
        InvoiceItem additionalInvoiceItem;
        boolean isAborted = false;
        boolean shouldUpdateDescription = false;
        DateTime rescheduleDate;
        boolean wasRescheduled = false;
        int priorCallInvocationCalls = 0;
        int onSuccessInvocationCalls = 0;

        Function<Invoice, List<InvoiceItem>> taxItems = NoTaxItems;
        Function<Invoice, InvoiceGroupingResult> grpResult = NullInvoiceGroupingResult;

        Function<Iterable<PluginProperty>, Boolean> checkPluginProperties = NoCheckPluginProperties;

        @Override
        public PriorInvoiceResult priorCall(final InvoiceContext invoiceContext, final Iterable<PluginProperty> pluginProperties) {
            priorCallInvocationCalls++;

            assertTrue(checkPluginProperties.apply(pluginProperties));

            wasRescheduled = invoiceContext.isRescheduled();
            return new PriorInvoiceResult() {

                @Override
                public boolean isAborted() {
                    return isAborted;
                }

                @Override
                public DateTime getRescheduleDate() {
                    return rescheduleDate;
                }
            };
        }

        @Override
        public List<InvoiceItem> getAdditionalInvoiceItems(final Invoice invoice, final boolean isDryRun, final Iterable<PluginProperty> pluginProperties, final CallContext callContext) {

            assertTrue(checkPluginProperties.apply(pluginProperties));

            if (isDryRun) {
                assertEquals(Iterables.size(pluginProperties), 2);
                final Optional<PluginProperty> p1 = Iterables.tryFind(pluginProperties, new Predicate<PluginProperty>() {
                    @Override
                    public boolean apply(final PluginProperty input) {
                        return input.getKey().equals("DRY_RUN_CUR_DATE");
                    }
                });
                assertTrue(p1.isPresent());

                final Optional<PluginProperty> p2 = Iterables.tryFind(pluginProperties, new Predicate<PluginProperty>() {
                    @Override
                    public boolean apply(final PluginProperty input) {
                        return input.getKey().equals("DRY_RUN_TARGET_DATE");
                    }
                });
                assertTrue(p2.isPresent());
            }

            if (shouldThrowException) {
                throw new InvoicePluginApiRetryException();
            } else if (additionalInvoiceItem != null) {
                return ImmutableList.<InvoiceItem>of(additionalInvoiceItem);
            } else if (taxItems.apply(invoice) != null) {
                return taxItems.apply(invoice);
            } else if (shouldUpdateDescription) {
                final List<InvoiceItem> updatedInvoiceItems = new LinkedList<InvoiceItem>();
                for (final InvoiceItem invoiceItem : invoice.getInvoiceItems()) {
                    final InvoiceItem updatedInvoiceItem = Mockito.spy(invoiceItem);
                    Mockito.when(updatedInvoiceItem.getDescription()).thenReturn(String.format("[plugin] %s", invoiceItem.getId()));
                    updatedInvoiceItems.add(updatedInvoiceItem);
                }
                return updatedInvoiceItems;
            } else {
                return ImmutableList.<InvoiceItem>of();
            }
        }

        @Override
        public InvoiceGroupingResult getInvoiceGrouping(final Invoice invoice, final boolean dryRun, final Iterable<PluginProperty> pluginProperties, final CallContext context) {
            assertTrue(checkPluginProperties.apply(pluginProperties));

            return grpResult.apply(invoice);
        }

        @Override
        public OnSuccessInvoiceResult onSuccessCall(final InvoiceContext invoiceContext, final Iterable<PluginProperty> pluginProperties) {
            onSuccessInvocationCalls++;
            assertTrue(checkPluginProperties.apply(pluginProperties));

            return null;
        }

        @Override
        public OnFailureInvoiceResult onFailureCall(final InvoiceContext invoiceContext, final Iterable<PluginProperty> pluginProperties) {
            assertTrue(checkPluginProperties.apply(pluginProperties));

            return null;
        }
    }
}
