# 🔋 زنگ هوشمند باتری و بهینه‌ساز (نسخه ۱.۰.۰ — ویرایش فنی کامل)

[![Download APK](https://img.shields.io/badge/دانلود_آخرین_نسخه-battery--alarm.apk-10b981?style=for-the-badge&logo=android&logoColor=white)](https://github.com/hafgo096-ux/battery-alarm/releases/latest/download/battery-alarm.apk)
[![Latest Release](https://img.shields.io/badge/GitHub_Release-latest-06b6d4?style=for-the-badge&logo=github)](https://github.com/hafgo096-ux/battery-alarm/releases/latest)
[![Build](https://img.shields.io/badge/Build-GitHub_Actions-success?style=for-the-badge&logo=githubactions&logoColor=white)](https://github.com/hafgo096-ux/battery-alarm/actions)

> اپلیکیشن اندروید پایش شارژ باتری (Overcharge Protection)، هشدار صوتی فارسی، پایش سخت‌افزار،
> بهینه‌سازی رم و اسکنر هوشمند فایل‌ها — همراه با **بازنویسی فنی نسخه ۱** برای رفع باگ‌های
> ذخیره‌سازی تنظیمات، کنترل بلندی صدا و کاهش چشمگیر مصرف باتری.

---

## 📥 لینک‌های دسترسی (به‌روز شده)

| مورد | لینک | توضیح |
| :--- | :--- | :--- |
| 📲 **دانلود مستقیم APK** | [**battery-alarm.apk**](https://github.com/hafgo096-ux/battery-alarm/releases/latest/download/battery-alarm.apk) | همیشه آخرین فایل امضاشده نسخه ۱ را می‌دهد |
| 🏷️ **آخرین انتشار** | [Releases (latest)](https://github.com/hafgo096-ux/battery-alarm/releases/latest) | صفحه رسمی انتشار، دارایی‌ها و وضعیت ساخت |
| 🧪 **وضعیت ساخت خودکار** | [GitHub Actions](https://github.com/hafgo096-ux/battery-alarm/actions) | ساخت APK با کلید امضای اختصاصی در هر Push |
| 🌐 **رابط کاربری وب (پیش‌نمایش)** | [index.html](index.html) · [assets/index.html](assets/index.html) | همان رابط WebView با قابلیت اجرای ۲۰۰ تست واقعی |

> نصب: فایل APK را دانلود و نصب کنید (اجازه «نصب از منابع نامشخص» لازم است).

---

## 🐞 گزارش تحلیل و رفع باگ‌ها (نسخه ۱ — ویرایش فنی)

### الف) باگ «فراموش شدن تنظیمات» (گزارش اصلی کاربر) ✅ رفع شد
| # | مشکل | توضیح فنی | اصلاح |
| :- | :--- | :--- | :--- |
| ۱ | تنظیمات هیچ‌گاه ذخیره نمی‌شد | صفحه با `file:///android_asset/index.html` بارگذاری می‌شد؛ دسترسی به `localStorage` در WebView روی اسکیم `file://` در اندروید **مسدود** است و خطای `SecurityError` در `try/catch` بی‌صدا خورده می‌شد | بارگذاری با `WebViewAssetLoader` روی دامنه امن `https://appassets.androidplatform.net/assets/` ⇒ DOM Storage فعال شد |
| ۲ | هیچ مسیر «بازخوانی» تنظیمات وجود نداشت | فقط `localStorage.setItem` وجود داشت و در هیچ‌جای کد `getItem` فراخوانی نمی‌شد؛ پس در هر اجرا UI روی مقدارهای پیش‌فرض HTML می‌ماند | `MonitorPrefs` + `settingsJson()` / `setSetting()` / `loadSettingsFromNative()`: همه تنظیمات در `SharedPreferences` ذخیره و در هر اجرا بازخوانی می‌شوند |
| ۳ | ذخیره‌سازی نامتقارن | درصد هدف به نیتیو ذخیره می‌شد ولی سوییچ‌ها فقط در حافظه صفحه بودند | هر ۱۵ کلید تنظیمات (هدف، کم‌شارژ، دمای بحرانی، حجم صدا، توقف خودکار، ویبره، آژیر، حالت پایش …) تایپ‌دار ذخیره می‌شوند |
| ۴ | مقدارهای نامعتبر کنترل نمی‌شد | ورودی بدون محدوده ذخیره می‌شد | `clamp`: هدف ۵۰–۱۰۰، کم‌شارژ ۵–۵۰، دما ۳۵–۶۰، صدا ۰–۱۰۰، توقف خودکار ۰–۱۲۰s، فاصله هشدار ۱۰–۳۰۰s |

### ب) باگ‌های مصرف باتری ✅ رفع شد
| # | مشکل | اثر | اصلاح |
| :- | :--- | :--- | :--- |
| ۵ | `WakeLock.acquire()` **بدون timeout** | CPU هرگز Sleep نمی‌کرد ⇒ مصرف مداوم | حذف کامل WakeLock و مجوز `WAKE_LOCK` |
| ۶ | `Timer` نگهبان هر ۶۰ ثانیه | بیداری دائمی پردازنده | حذف کامل Timer/watchdog |
| ۷ | `webView.setKeepScreenOn(true)` | نمایشگر هرگز خاموش نمی‌شد (بزرگ‌ترین مصرف‌کننده) | حذف شد |
| ۸ | Polling مکرر JS→Java (هر ۲.۵s) | خواندن مداوم `/proc/stat` و `/proc/meminfo` | پایش **رویدادمحور** + کش‌گذاری (CPU ۱.۵s، رم ۱.۲s، حافظه ۴s) + بازه UI ۴s |
| ۹ | ادامه تایمرهای JS در پس‌زمینه | رندر بی‌فایده در پس‌زمینه | `onPause/onResume` + `pauseTimers()` + `visibilitychange` |
| ۱۰ | آژیر بی‌پایان | پخش مداوم صدا | «توقف خودکار زنگ» (۱۵/۳۰/۴۵/۶۰/۱۲۰ ثانیه یا دستی) |
| ۱۱ | درخواست معافیت باتری در هر اجرا | اجرای مکرر Intent سیستمی | فقط یک‌بار (فلگ ذخیره‌شده) |
| ۱۲ | امکان خاموش‌کردن پایش نبود | سرویس دائمی همیشه فعال | «حالت کم‌مصرف» با `JobScheduler` هر ۱۵ دقیقه + کلید خاموش‌کردن کامل |

**نتیجه:** پایش فقط با رویداد واقعی باتری پردازش می‌کند؛ در حالت کم‌مصرف فعالیت پس‌زمینه تقریباً صفر است.
هیچ WakeLock فعالی در برنامه وجود ندارد (این عدد در پنل «مصرف باتری» داخل اپ زنده نمایش داده می‌شود).

### ج) قابلیت جدید: کنترل بلندی صدای هشدار 🔊 ✅ اضافه شد
- اسلایدر ۰…۱۰۰٪ در تب تنظیمات با نمایش زنده مقدار و پیش‌تنظیم ۰/۵۰/۱۰۰.
- دکمه «تست زنگ با این بلندی» (۶ ثانیه و سپس توقف خودکار).
- سوییچ «اعمال روی بلندی زنگ گوشی» (تغییر واقعی `STREAM_ALARM`).
- دکمه هدر (🔊/🔉/🔇) اکنون بی‌صدا سازی واقعی و بازیابی آخرین بلندی را انجام می‌دهد.
- پس از پایان پیام فارسی، آژیر حلقه‌ای تا مدت تعیین‌شده ادامه می‌یابد (قابل خاموش‌کردن).

### د) سایر باگ‌ها و اصلاحات کیفی
| # | مشکل | اصلاح |
| :- | :--- | :--- |
| ۱۳ | حذف فایلِ ناموجود «موفق» گزارش می‌شد | خروجی JSON صادقانه (`not_found` / `permission_denied_or_scoped_storage`) |
| ۱۴ | مقدار آزادسازی رم با `Math.random()` ساخته می‌شد | سنجش واقعی `MemoryInfo` قبل/بعد و گزارش تغییر حقیقی |
| ۱۵ | داده ساختگی سخت‌افزار (لود تصادفی هسته‌ها، ولتاژ/دما/سلامت پیش‌فرض) | همه مقادیر از API واقعی؛ در نبود دسترسی «—» نمایش داده می‌شود |
| ۱۶ | فهرست فایل‌ها/کش تزئینی بود | برچسب صریح «نمونه (دمو)» + اسکن واقعی برنامه‌های با دسترسی حساس |
| ۱۷ | مجموعه «۱۰۰ تست» همیشه PASS می‌داد | **۲۰۰ آزمون واقعی** با گزارش PASS/FAIL/SKIP |
| ۱۸ | نشتی حافظه WebView | `onDestroy` با آزادسازی کامل و حذف interfaceها |
| ۱۹ | مجوزهای اضافی | حذف `WAKE_LOCK`، `SCHEDULE_EXACT_ALARM`، `USE_EXACT_ALARM`، `READ/WRITE_EXTERNAL_STORAGE` |
| ۲۰ | `setAllowUniversalAccessFromFileURLs(true)` و MixedContent باز | حذف شد؛ بارگذاری از دامنه امن داخلی |
| ۲۱ | تکرار هشدار کم‌شارژ/دما | ماشین وضعیت re-arm + بازه ضدتکرار (هدف یک‌بار در هر چرخه) |
| ۲۲ | تست صدا بدون کنترل بلندی | همه مسیرهای پخش از مقدار ذخیره‌شده کاربر استفاده می‌کنند |

---

## 🧪 تست و اعتبارسنجی
- **۲۰۰ آزمون واقعی داخل اپ:** تب «تست سیستم» → «اجرای ۲۰۰ تست واقعی» (گروه‌ها: BRIDGE، SETTINGS، PERSIST، VALIDATION، VOLUME، BATTERY، POWER، UI، DATA، PERF).
- تنظیمات در پایان تست‌ها به مقدار اصلی کاربر بازگردانده می‌شود.
- ساخت APK امضاشده با GitHub Actions در هر Push و انتشار در Releases.

---

## ⚙️ حالت‌های پایش و مصرف
| حالت | مکانیزم | مصرف باتری | کاربرد |
| :--- | :--- | :--- | :--- |
| **پایش زنده (پیش‌فرض)** | سرویس Foreground + گیرنده `ACTION_BATTERY_CHANGED` (رویدادمحور، بدون WakeLock) | بسیار کم | هشدار دقیق در لحظه رسیدن به درصد هدف |
| **کم‌مصرف (Ultra Saver)** | فقط `JobScheduler` هر ۱۵ دقیقه | تقریباً صفر | شب/مسافرت |
| **خاموش** | هیچ سرویس و تایمری | صفر | وقتی فقط UI و ابزارها لازم است |

---

## 🔐 مشخصات امضا و انتشار
- **Store File:** `batteryalarm-release-key.jks` — **Key Alias:** `batteryalarm_release_alias`
- **طرح‌های امضا:** v1 + v2 + v3 (Release Signed)
- **Package ID:** `com.persian.batteryalarm.optimizer`
- **Version Code:** `1` — **Version Name:** `1.0.0` (نسخه ۱ حفظ شده)
- **minSdk:** API 24 (اندروید ۷) — **targetSdk/compileSdk:** 34
- **CI:** Gradle 8.9 + JDK 17 + AGP 8.7.3

---

## 🗂️ ساختار پروژه
```
app/src/main/java/com/persian/batteryalarm/optimizer/
├── MainActivity.java             # میزبان WebView (AssetLoader، چرخه عمر، مجوزها)
├── MonitorPrefs.java             # 🆕 مخزن واحد تنظیمات (رفع باگ ذخیره‌سازی)
├── WebBridge.java                # پل JS↔Java (تنظیمات، حجم صدا، پایش، رم، فایل)
├── SysInfoBridge.java            # داده واقعی سخت‌افزار/باتری با کش
├── BatteryWatchService.java      # سرویس رویدادمحور (بدون WakeLock)
├── BatteryMonitorJobService.java # 🆕 حالت کم‌مصرف (Job هر ۱۵ دقیقه)
├── AlertEngine.java              # 🆕 موتور تصمیم‌گیری هشدار + ماشین وضعیت
├── BatteryReceiver.java          # گیرنده سبک رویداد باتری
└── BootReceiver.java             # بازگرداندن پایش پس از ری‌استارت
app/src/main/assets/index.html    # رابط کاربری (همان index.html و assets/index.html)
```

> **حریم خصوصی:** هیچ داده‌ای به سرور ارسال نمی‌شود؛ تمام پردازش‌ها محلی و آفلاین است.
