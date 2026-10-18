# 🔋 زنگ هوشمند باتری و بهینه‌ساز (نسخه ۱.۰.۲ — اصلاح جامع ۱۰ هزار تسته)

[![Download APK](https://img.shields.io/badge/دانلود_آخرین_نسخه-battery--alarm.apk-10b981?style=for-the-badge&logo=android&logoColor=white)](https://github.com/hafgo096-ux/battery-alarm/releases/latest/download/battery-alarm.apk)
[![Latest Release](https://img.shields.io/badge/GitHub_Release-v1.0.2-06b6d4?style=for-the-badge&logo=github)](https://github.com/hafgo096-ux/battery-alarm/releases/latest)
[![Build](https://img.shields.io/badge/Build-GitHub_Actions-success?style=for-the-badge&logo=githubactions&logoColor=white)](https://github.com/hafgo096-ux/battery-alarm/actions)

> اپلیکیشن اندروید پایش شارژ باتری (Overcharge Protection)، هشدار صوتی فارسی، پایش سخت‌افزار،
> بهینه‌سازی رم و اسکنر هوشمند فایل‌ها — نسخه ۱.۰.۲ با **اصلاح جامع باگ‌های گزارش‌شده در تست ۱۰ هزار باره**:
> هوش فایل دقیق (بدون اتهام دروغین به تلگرام)، گالری شیک تصویر/ویدیو، و بهینه‌سازی رم سبک.

---

## 📥 لینک‌های دسترسی (به‌روز شده برای نسخه ۱.۰.۲)

| مورد | لینک | توضیح |
| :--- | :--- | :--- |
| 📲 **دانلود مستقیم APK** | [**battery-alarm.apk**](https://github.com/hafgo096-ux/battery-alarm/releases/latest/download/battery-alarm.apk) | همیشه آخرین فایل امضاشده (فعلاً نسخه ۱.۰.۲) را می‌دهد |
| 🏷️ **آخرین انتشار** | [Releases (latest)](https://github.com/hafgo096-ux/battery-alarm/releases/latest) | صفحه رسمی انتشار نسخه ۱.۰.۲، دارایی‌ها و وضعیت ساخت |
| 🧪 **وضعیت ساخت خودکار** | [GitHub Actions](https://github.com/hafgo096-ux/battery-alarm/actions) | ساخت APK با کلید امضای اختصاصی در هر Push |
| 🌐 **رابط کاربری وب (پیش‌نمایش)** | [index.html](index.html) · [assets/index.html](assets/index.html) | همان رابط WebView با قابلیت اجرای ۲۵۰+ تست واقعی |

> نصب: فایل APK را دانلود و نصب کنید (اجازه «نصب از منابع نامشخص» لازم است).
> اگر نسخه ۱.۰.۱ را دارید، نسخه ۱.۰.۲ با همان امضا روی آن نصب می‌شود (بدون نیاز به حذف).

---

## 🆕 گزارش تحلیل و رفع باگ‌ها (نسخه ۱.۰.۲ — اصلاح جامع)

### الف) باگ حیاتی هوش فایل — اتهام اشتباه به تلگرام ✅ رفع شد
**ریشه باگ:** در ۱.۰.۱ متد `getRiskyApps()` از `requestedPermissions` (تمام مجوزهای درج‌شده در manifest) استفاده می‌کرد، نه مجوزهای واقعاً اعطا شده (`GRANTED`). نتیجه: هر برنامه‌ای که در manifest مکان را درخواست کرده باشد — مثل تلگرام برای قابلیت اختیاری «اشتراک‌گذاری مکان» — به اشتباه به عنوان «پرخطر — دسترسی به مکان» نشان داده می‌شد، حتی اگر کاربر هرگز اجازه مکان را نداده باشد.

| # | مشکل | اصلاح دقیق |
| :- | :--- | :--- |
| ۱ | تلگرام بدون مجوز واقعی به‌عنوان «دارای دسترسی مکان» نشان داده می‌شد | بررسی فقط با `requestedPermissionsFlags & REQUESTED_PERMISSION_GRANTED` — فقط مجوزهایی که کاربر در تنظیمات اندروید > مجوزها واقعاً اعطا کرده |
| ۲ | برنامه‌های تک‌مجوزه (۱ مجوز) به اشتباه پرخطر محسوب می‌شدند | حداقل ۲ مجوز حساس GRANTED یا ترکیب بحرانی (SMS+مکان، SMS+CallLog+مخاطبان) لازم است |
| ۳ | پیام دلیل مبهم بود | جمله فارسی دقیق: «دسترسی فعال به پیامک‌ها و موقعیت مکانی — ترکیب حساس» + لیست `grantedPerms` |
| ۴ | بدون آیکون، شناسایی سخت بود | آیکون واقعی هر برنامه با `PackageManager.loadLabel().getApplicationIcon()` → Base64 PNG + کارت با `app-icon-box` شیک |

**تست ۱۰ هزار باره:** استرس تست با ۱۰K تکرار `clamp`، `toFaDigits`، `formatBytes`، سوئیچ تب، fuzz ورودی‌های نامعتبر، و بررسی خاص `getRiskyApps` که تلگرام بدون مجوز واقعی flag نشود.

### ب) مدیریت تصاویر و ویدیو — بازنویسی کامل ✅
**مشکل قبلی:** نمایش نام فایل انگلیسی درهم (`IMG_2024_1234.jpg`, `Telegram_Video_cache.mp4`) + بدون پیش‌نمایش + بدون پلیر.

| # | قابلیت جدید | معماری بهینه |
| :- | :--- | :--- |
| ۵ | برچسب فارسی شیک | به‌جای نام فایل: «تصویر ۱»، «تصویر ۲» و «ویدیو ۱»، «ویدیو ۲» با آیکون 🖼️/🎬 — محاسبه در نیتیو با `kind` و `persianLabel` |
| ۶ | گرید حرفه‌ای | CSS Grid 2 ستونه (3 ستونه روی تبلت)، کارت گرد 16px با `media-badge` رنگی و `media-duration` برای ویدیو |
| ۷ | Thumbnail سبک | `loadThumbnail(320px)` روی API 29+ و fallback `BitmapFactory` + `MediaMetadataRetriever` برای ویدیو؛ فشرده JPEG 75% + lazy-load با stagger (8 تای اول فوری، بقیه 40ms فاصله) تا فشار صفر |
| ۸ | لایت‌باکس تصویر | فول‌اسکرین `rgba(0,0,0,0.92)` با blur، هدر با سایز/ابعاد/تاریخ، ناوبری ←/→ لمسی، فوتر با چیپ‌های اطلاعات |
| ۹ | ویدیو پلیر فوق‌بهینه | `<video playsinline preload=metadata>` + کنترل سفارشی (play/pause، progress bar، زمان فارسی، بی‌صدا، fullscreen، ±10s)، `requestAnimationFrame` برای progress، auto-hide 3s، hardware-accelerated |

### ج) بهینه‌سازی رم — سبک و بدون فشار ✅
**مشکل قبلی:** فقط یک دکمه «پاکسازی کش خود برنامه» بدون لیست پردازش‌ها + `killBackgroundProcesses` گزارش مبهم.

| # | بهبود | جزئیات |
| :- | :--- | :--- |
| ۱۰ | کش‌گذاری و توقف در پس‌زمینه | CPU 1.5s، رم 1.2s، حافظه 4s، باتری 1s؛ `onPause()/onResume()` + `visibilitychange` + بدون WakeLock |
| ۱۱ | لیست پردازش‌های واقعی | `ActivityManager.getRunningAppProcesses()` + `getProcessMemoryInfo()` → PSS واقعی + مرتب‌سازی نزولی + `getAppIcon()` برای هر پردازش |
| ۱۲ | انتخاب و بستن هوشمند | چک‌باکس + «انتخاب همه» + `optimizeRam(selectedPkgs)` با گزارش صادقانه `killedCount` و `freedMb` — بدون فشار به گوشی (فقط پس‌زمینه مجاز) |
| ۱۳ | نیتیو استرس | متد جدید `stressTestNative(10000)` برای 10K تکرار موتور AlertEngine |

### د) کیفیت مجموعه تست: ۲۵۰+ آزمون ✅
- گروه‌های جدید: `STRESS` (10K clamp/toFaDigits/formatBytes/tab)، `FUZZ` (ورودی‌های نامعتبر)، `PERMISSION` (مجوز GRANTED، تلگرام)، `MEDIA` (برچسب فارسی، آیکون، lightbox، پلیر)، `RAM` (سبکی، intervals ≤3).
- تست سبکی: حجم صفحه <250KB، تعداد تایمرها ≤3، تست نیتیو 10K با گزارش ops/sec.

---

## 🐞 گزارش قبلی (۱.۰.۱)

### الف) باگ‌های بحرانی رابط کاربری ✅ رفع شد
| # | مشکل | اثر | اصلاح |
| :- | :--- | :--- | :--- |
| ۱ | ثابت `FA_DIGITS` تعریف نشده | `ReferenceError` | تعریف + `try/catch` ایزوله |
| ۲ | ۶ کنترل تنظیمات جاافتاده | درصد روی ۸۵ قفل | ۱۰ کنترل کامل |
| ۳ | کرش «حذف همه» | ReferenceError | بازنویسی صادقانه |
| ۴ | گزارش حذف جعلی | پیام دروغین | حذف مسیر جعلی |
| ۵ | تب‌های فایل تکراری | هایلایت اشتباه | ۴ دکمه یکتا |

### ب) اسکنر واقعی ✅
- کش برنامه‌ها با `StorageStatsManager`، فایل‌های حجیم با `MediaStore`، پاکسازی `freeOwnMemory()`، مدیریت در `openAppInfo()`.

### ج) سرویس و هشدار ✅
- تست زنگ با پایش خاموش، هشدار کم‌مصرف، `MY_PACKAGE_REPLACED`، فرکانس CPU، باتری ۰٪.

---

## 🧪 تست و اعتبارسنجی
- **۲۵۰+ آزمون داخل اپ:** تب «تست سیستم» → «اجرای N تست واقعی» (شمارش داینامیک؛ گروه‌ها: BRIDGE, SETTINGS, PERSIST, VALIDATION, VOLUME, BATTERY, POWER, UI, DATA, PERF, TYPE, RANGE, SECURITY, STRESS, FUZZ, PERMISSION, MEDIA, RAM).
- استرس نیتیو: `stressTestNative(10000)` با 10K تکرار clamp/percentOf/tempOf.
- در مرورگر وب تست‌های سخت‌افزاری درست SKIP می‌شوند.

---

## ⚙️ حالت‌های پایش و مصرف
| حالت | مکانیزم | مصرف | کاربرد |
| :--- | :--- | :--- | :--- |
| **پایش زنده** | Foreground + `ACTION_BATTERY_CHANGED` (بدون WakeLock) | بسیار کم | دقیق |
| **کم‌مصرف** | `JobScheduler` هر 15 دقیقه + پیام فارسی | تقریباً صفر | شب/سفر |
| **خاموش** | هیچ سرویس | صفر | فقط UI |

---

## 🔐 مشخصات امضا
- **Package ID:** `com.persian.batteryalarm.optimizer`
- **Version:** 1.0.2 (Code: 3) — **minSdk 24 — targetSdk 34**
- **Key Alias:** `batteryalarm_release_alias` — v1+v2+v3

---

## 🗂️ ساختار
```
app/src/main/java/com/persian/batteryalarm/optimizer/
├── MainActivity.java             # WebView + allowContentAccess برای thumbnail
├── MonitorPrefs.java             # SharedPreferences
├── WebBridge.java                # پل اصلی ۱.۰.۲: مجوز GRANTED، آیکون، گالری، رم
├── SysInfoBridge.java            # سخت‌افزار با کش
├── BatteryWatchService.java      # رویدادمحور بدون WakeLock
├── BatteryMonitorJobService.java # کم‌مصرف + صدای هشدار
├── AlertEngine.java              # تصمیم‌گیری
└── BatteryReceiver/BootReceiver  # رویداد باتری/بوت
index.html                        # مرجع واحد UI (با syncWebUi به assets)
```

> **حریم خصوصی:** آفلاین، بدون ارسال داده. مجوزهای آمار مصرف/رسانه فقط با تأیید صریح.

