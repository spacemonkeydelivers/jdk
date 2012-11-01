/*
 * Copyright (c) 2005, 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.util.locale.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Locale.Builder;
import java.util.ResourceBundle.Control;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.spi.LocaleServiceProvider;
import sun.util.logging.PlatformLogger;

/**
 * An instance of this class holds a set of the third party implementations of a particular
 * locale sensitive service, such as {@link java.util.spi.LocaleNameProvider}.
 *
 * @author Naoto Sato
 * @author Masayoshi Okutsu
 */
public final class LocaleServiceProviderPool {

    /**
     * A Map that holds singleton instances of this class.  Each instance holds a
     * set of provider implementations of a particular locale sensitive service.
     */
    private static ConcurrentMap<Class<? extends LocaleServiceProvider>, LocaleServiceProviderPool> poolOfPools =
        new ConcurrentHashMap<>();

    /**
     * A Map containing locale service providers that implement the
     * specified provider SPI, keyed by a LocaleProviderAdapter.Type
     */
    private ConcurrentMap<LocaleProviderAdapter.Type, LocaleServiceProvider> providers =
        new ConcurrentHashMap<>();

    /**
     * A Map that retains Locale->provider mapping
     */
    private ConcurrentMap<Locale, List<LocaleProviderAdapter.Type>> providersCache =
        new ConcurrentHashMap<>();

    /**
     * Available locales for this locale sensitive service.  This also contains
     * JRE's available locales
     */
    private Set<Locale> availableLocales = null;

    /**
     * Provider class
     */
    private Class<? extends LocaleServiceProvider> providerClass;

    /**
     * Array of all Locale Sensitive SPI classes.
     *
     * We know "spiClasses" contains classes that extends LocaleServiceProvider,
     * but generic array creation is not allowed, thus the "unchecked" warning
     * is suppressed here.
     */
    @SuppressWarnings("unchecked")
    static final Class<LocaleServiceProvider>[] spiClasses =
                (Class<LocaleServiceProvider>[]) new Class<?>[] {
        java.text.spi.BreakIteratorProvider.class,
        java.text.spi.CollatorProvider.class,
        java.text.spi.DateFormatProvider.class,
        java.text.spi.DateFormatSymbolsProvider.class,
        java.text.spi.DecimalFormatSymbolsProvider.class,
        java.text.spi.NumberFormatProvider.class,
        java.util.spi.CurrencyNameProvider.class,
        java.util.spi.LocaleNameProvider.class,
        java.util.spi.TimeZoneNameProvider.class,
        java.util.spi.CalendarDataProvider.class
    };

    /**
     * A factory method that returns a singleton instance
     */
    public static LocaleServiceProviderPool getPool(Class<? extends LocaleServiceProvider> providerClass) {
        LocaleServiceProviderPool pool = poolOfPools.get(providerClass);
        if (pool == null) {
            LocaleServiceProviderPool newPool =
                new LocaleServiceProviderPool(providerClass);
            pool = poolOfPools.putIfAbsent(providerClass, newPool);
            if (pool == null) {
                pool = newPool;
            }
        }

        return pool;
    }

    /**
     * The sole constructor.
     *
     * @param c class of the locale sensitive service
     */
    private LocaleServiceProviderPool (final Class<? extends LocaleServiceProvider> c) {
        providerClass = c;

        // Add the JRE Locale Data Adapter implementation.
        providers.putIfAbsent(LocaleProviderAdapter.Type.JRE,
            LocaleProviderAdapter.forJRE().getLocaleServiceProvider(c));

        // Add the SPI Locale Data Adapter implementation.
        LocaleProviderAdapter lda = LocaleProviderAdapter.forType(LocaleProviderAdapter.Type.SPI);
        LocaleServiceProvider provider = lda.getLocaleServiceProvider(c);
        if (provider != null) {
            providers.putIfAbsent(LocaleProviderAdapter.Type.SPI, provider);
        }

        // Add the CLDR Locale Data Adapter implementation, if needed.
        lda =  LocaleProviderAdapter.forType(LocaleProviderAdapter.Type.CLDR);
        if (lda != null) {
            provider = lda.getLocaleServiceProvider(c);
            if (provider != null) {
                providers.putIfAbsent(LocaleProviderAdapter.Type.CLDR, provider);
            }
        }

        // Add the Host Locale Data Adapter implementation, if needed.
        lda =  LocaleProviderAdapter.forType(LocaleProviderAdapter.Type.HOST);
        if (lda != null) {
            provider = lda.getLocaleServiceProvider(c);
            if (provider != null) {
                providers.putIfAbsent(LocaleProviderAdapter.Type.HOST, provider);
            }
        }
    }

    static void config(Class<? extends Object> caller, String message) {
        PlatformLogger logger = PlatformLogger.getLogger(caller.getCanonicalName());
        logger.config(message);
    }

    /**
     * Lazy loaded set of available locales.
     * Loading all locales is a very long operation.
     */
    private static class AllAvailableLocales {
        /**
         * Available locales for all locale sensitive services.
         * This also contains JRE's available locales
         */
        static final Locale[] allAvailableLocales;

        static {
            Set<Locale> all = new HashSet<>();
            for (Class<? extends LocaleServiceProvider> c : spiClasses) {
                LocaleServiceProviderPool pool =
                    LocaleServiceProviderPool.getPool(c);
                all.addAll(pool.getAvailableLocaleList());
            }

            allAvailableLocales = all.toArray(new Locale[0]);
        }

        // No instantiation
        private AllAvailableLocales() {
        }
    }

    /**
     * Returns an array of available locales for all the provider classes.
     * This array is a merged array of all the locales that are provided by each
     * provider, including the JRE.
     *
     * @return an array of the available locales for all provider classes
     */
    public static Locale[] getAllAvailableLocales() {
        return AllAvailableLocales.allAvailableLocales.clone();
    }

    /**
     * Returns an array of available locales.  This array is a
     * merged array of all the locales that are provided by each
     * provider, including the JRE.
     *
     * @return an array of the available locales
     */
    public Locale[] getAvailableLocales() {
        Set<Locale> locList = getAvailableLocaleList();
        Locale[] tmp = new Locale[locList.size()];
        locList.toArray(tmp);
        return tmp;
    }

    private synchronized Set<Locale> getAvailableLocaleList() {
        if (availableLocales == null) {
            availableLocales = new HashSet<>();
            for (LocaleServiceProvider lsp : providers.values()) {
                Locale[] locales = lsp.getAvailableLocales();
                for (Locale locale: locales) {
                    availableLocales.add(getLookupLocale(locale));
                }
            }

            // Remove Locale.ROOT for the compatibility.
            availableLocales.remove(Locale.ROOT);
        }

        return availableLocales;
    }

    /**
     * Returns whether any provider for this locale sensitive
     * service is available or not, excluding JRE's one.
     *
     * @return true if any provider (other than JRE) is available
     */
    boolean hasProviders() {
        return providers.size() != 1 ||
               providers.get(LocaleProviderAdapter.Type.JRE) == null;
    }

    /**
     * Returns the provider's localized object for the specified
     * locale.
     *
     * @param getter an object on which getObject() method
     *     is called to obtain the provider's instance.
     * @param locale the given locale that is used as the starting one
     * @param params provider specific parameters
     * @return provider's instance, or null.
     */
    public <P extends LocaleServiceProvider, S> S getLocalizedObject(LocalizedObjectGetter<P, S> getter,
                                     Locale locale,
                                     Object... params) {
        return getLocalizedObjectImpl(getter, locale, true, null, params);
    }

    /**
     * Returns the provider's localized name for the specified
     * locale.
     *
     * @param getter an object on which getObject() method
     *     is called to obtain the provider's instance.
     * @param locale the given locale that is used as the starting one
     * @param key the key string for name providers
     * @param params provider specific parameters
     * @return provider's instance, or null.
     */
    public <P extends LocaleServiceProvider, S> S getLocalizedObject(LocalizedObjectGetter<P, S> getter,
                                     Locale locale,
                                     String key,
                                     Object... params) {
        return getLocalizedObjectImpl(getter, locale, false, key, params);
    }

    @SuppressWarnings("unchecked")
    private <P extends LocaleServiceProvider, S> S getLocalizedObjectImpl(LocalizedObjectGetter<P, S> getter,
                                     Locale locale,
                                     boolean isObjectProvider,
                                     String key,
                                     Object... params) {
        if (locale == null) {
            throw new NullPointerException();
        }

        // Check whether JRE is the sole locale data provider or not,
        // and directly call it if it is.
        if (!hasProviders()) {
            return getter.getObject(
                (P)providers.get(LocaleProviderAdapter.Type.JRE),
                locale, key, params);
        }

        List<Locale> lookupLocales = getLookupLocales(locale);

        Set<Locale> available = getAvailableLocaleList();
        for (Locale current : lookupLocales) {
            if (available.contains(current)) {
                S providersObj;

                for (LocaleProviderAdapter.Type type: findProviders(current)) {
                    LocaleServiceProvider lsp = providers.get(type);
                    providersObj = getter.getObject((P)lsp, locale, key, params);
                    if (providersObj != null) {
                        return providersObj;
                    } else if (isObjectProvider) {
                        config(LocaleServiceProviderPool.class,
                            "A locale sensitive service provider returned null for a localized objects,  which should not happen.  provider: "
                                + lsp + " locale: " + locale);
                    }
                }
            }
        }

        // not found.
        return null;
    }

    /**
     * Returns the list of locale service provider instances that support
     * the specified locale.
     *
     * @param locale the given locale
     * @return the list of locale data adapter types
     */
    private List<LocaleProviderAdapter.Type> findProviders(Locale locale) {
        List<LocaleProviderAdapter.Type> providersList = providersCache.get(locale);
        if (providersList == null) {
            for (LocaleProviderAdapter.Type type : LocaleProviderAdapter.getAdapterPreference()) {
                LocaleServiceProvider lsp = providers.get(type);
                if (lsp != null) {
                    if (lsp.isSupportedLocale(locale)) {
                        if (providersList == null) {
                            providersList = new ArrayList<>(2);
                        }
                        providersList.add(type);

                    }
                }
            }
            if (providersList == null) {
                providersList = NULL_LIST;
            }
            List<LocaleProviderAdapter.Type> val = providersCache.putIfAbsent(locale, providersList);
            if (val != null) {
                providersList = val;
            }
        }
            return providersList;
        }

    /**
     * Returns a list of candidate locales for service look up.
     * @param locale the input locale
     * @return the list of candidate locales for the given locale
     */
    private static List<Locale> getLookupLocales(Locale locale) {
        // Note: We currently use the default implementation of
        // ResourceBundle.Control.getCandidateLocales. The result
        // returned by getCandidateLocales are already normalized
        // (no extensions) for service look up.
        List<Locale> lookupLocales = Control.getNoFallbackControl(Control.FORMAT_DEFAULT)
                                            .getCandidateLocales("", locale);
        return lookupLocales;
    }

    /**
     * Returns an instance of Locale used for service look up.
     * The result Locale has no extensions except for ja_JP_JP
     * and th_TH_TH
     *
     * @param locale the locale
     * @return the locale used for service look up
     */
    static Locale getLookupLocale(Locale locale) {
        Locale lookupLocale = locale;
        if (locale.hasExtensions()
                && !locale.equals(JRELocaleConstants.JA_JP_JP)
                && !locale.equals(JRELocaleConstants.TH_TH_TH)) {
            // remove extensions
            Builder locbld = new Builder();
            try {
                locbld.setLocale(locale);
                locbld.clearExtensions();
                lookupLocale = locbld.build();
            } catch (IllformedLocaleException e) {
                // A Locale with non-empty extensions
                // should have well-formed fields except
                // for ja_JP_JP and th_TH_TH. Therefore,
                // it should never enter in this catch clause.
                config(LocaleServiceProviderPool.class,
                       "A locale(" + locale + ") has non-empty extensions, but has illformed fields.");

                // Fallback - script field will be lost.
                lookupLocale = new Locale(locale.getLanguage(), locale.getCountry(), locale.getVariant());
            }
        }
        return lookupLocale;
    }

    /**
     * A dummy locale service provider list that indicates there is no
     * provider available
     */
    private static List<LocaleProviderAdapter.Type> NULL_LIST =
        Collections.emptyList();

    /**
     * An interface to get a localized object for each locale sensitive
     * service class.
     */
    public interface LocalizedObjectGetter<P extends LocaleServiceProvider, S> {
        /**
         * Returns an object from the provider
         *
         * @param lsp the provider
         * @param locale the locale
         * @param key key string to localize, or null if the provider is not
         *     a name provider
         * @param params provider specific params
         * @return localized object from the provider
         */
        public S getObject(P lsp,
                           Locale locale,
                           String key,
                           Object... params);
    }
}