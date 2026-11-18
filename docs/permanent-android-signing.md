# إعداد مفتاح توقيع أندرويد الدائم (مرة واحدة للمالك)
لا يوجد مفتاح دائم حاليًا. النسخ 0.2.0 و0.2.1 كانت debug. يجب المحافظة على نفس مفتاح التوقيع الخاص عند كل تحديث.
1. على جهاز موثوق يتوفر فيه Java keytool، أنشئ مفتاحًا محليًا في مجلد خارج المستودع:
   `keytool -genkeypair -v -keystore gaz-release.p12 -storetype PKCS12 -alias gaz -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Gaz Distribution"`
   اختر كلمة مرور قوية وأدخلها محليًا، ولا ترسلها في المحادثة. استخدم نفس كلمة المرور للمفتاح والمخزن. احتفظ بنسختين مشفرتين خارج الجهاز.
2. في GitHub Actions repository secrets، خزّن محتوى الملف بترميز Base64 باسم GAZ_KEYSTORE_BASE64 وكلمة المرور باسم GAZ_KEYSTORE_PASSWORD. لا تضف أيًا منهما في الكود أو Pages أو Issues أو logs. Base64 ليس تشفيرًا. مثال PowerShell مع gh CLI سبق تسجيل الدخول إليه:
   `[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path .\gaz-release.p12).Path)) | gh secret set GAZ_KEYSTORE_BASE64 --repo SalehAliAbuBaker/gaz-distribution`
   ثم `gh secret set GAZ_KEYSTORE_PASSWORD --repo SalehAliAbuBaker/gaz-distribution` وأدخل كلمة المرور في المحطة المحلية فقط.
3. اعرض بصمة الشهادة العامة باستخدام `keytool -list -v -keystore gaz-release.p12 -alias gaz`، ثم احفظ بصمة SHA256 في Actions repository variable باسم GAZ_SIGNER_SHA256. البصمة ليست سرًا.
4. شغّل للمرة الأولى workflow المسمى Build permanently signed Android pairing test يدويًا من main. يفشل دون الأسرار والبصمة المطابقة. يبني release غير debug ويختبره ويوقعه وينشر APK كتجربة مستقلة. لا يفترض نجاحه قبل تشغيله.
5. قبل الانتقال من 0.2.1 احفظ النسخة الاحتياطية الخارجية واختبرها. بعد تثبيت الإصدار الموقع بمفتاح جديد، استعد البيانات قبل تعيين الجوال الأم. لا تحذف أي جهاز مقترن أو تستعد نسخة قديمة بعد الربط قبل تنفيذ آلية استرداد السلطة.
6. لا تغيّر المفتاح بعد اعتماده ولا تنشر debug APK فوق الإصدار الموقع. يجب أن يتحقق كل إصدار لاحق من البصمة نفسها. نقل السلطة ونسخ مفاتيح الاقتران غير متاحين حاليًا.
