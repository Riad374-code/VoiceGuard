# Slide 1 - VoiceGuard / Gercek Zamanli Cok Modlu Dolandiricilik Tespiti

## Kisa Aciklama

Bu bolumde projenin ana teknik yontemi anlatilir. Sistem, telefon gorusmesi sirasinda konusmayi gercek zamanli olarak dinler, sesi yazili metne cevirir, metindeki sosyal muhendislik kaliplarini analiz eder ve ayni anda sesin akustik ozelliklerinden sentetik/deepfake ses ihtimalini kontrol eder. Son adimda ses ve metin sonuclari tek bir risk puaninda birlestirilir. Amac, kullaniciyi gorusme bitmeden once uyarmak ve dolandiricilik ihtimalini anlik olarak gostermektir.

## 1. STT Modulu - Konusmayi Metne Donusturme

Bu modul, telefon gorusmesindeki sesi anlik olarak yazili metne cevirir. Dolandiricilik tespitinin ilk adimi budur, cunku NLP modeli metin uzerinden calisir. Hedef, dusuk gecikme ile yuksek dogruluk saglamaktir. Sistem, Turkce konusma, lehce ve aksan farkliliklari icin optimize edilir. Ana STT motoru olarak Google STT veya Azure STT kullanilabilir; biri hata verdiginde digeri yedek motor olarak devreye girebilir. Bu sayede uygulama tek bir servis bagimliligina kalmaz.

STT tarafinda hedef metrikler WER oraninin %12'nin altinda, RTF oraninin ise 0.3'un altinda olmasidir. WER, transkripsiyon hatasini; RTF ise sesin ne kadar hizli islendiginin olcusunu gosterir. Bu metrikler, sistemin gercek telefon gorusmelerinde kullanilabilecek kadar hizli ve dogru calistigini gostermek icin kullanilir. KVKK uyumlulugu icin ses verisi gereksiz sekilde saklanmaz, isleme mumkun oldugunca yerel veya kontrollu ortamda yapilir.

## 2. NLP Modulu - Sosyal Muhendislik Tespiti

Bu modul, STT tarafindan uretilen metni analiz ederek dolandiricilik sinyallerini arar. Turkce ince ayarli BERTurk benzeri transformer modelleri kullanilarak, Turkce konusmalardaki anlam, baglam ve niyet daha iyi yakalanir. Sistem yalnizca tek tek kelimelere bakmaz; cumlenin amacini, konusmanin yonunu ve kullanici uzerinde kurulan baskiyi de degerlendirir.

Model ozellikle aciliyet, otorite, korku, odul vaadi, hesap guvenligi bahanesi, para transferi talebi, kimlik dogrulama baskisi ve gizlilik yonlendirmesi gibi sosyal muhendislik kaliplarini tespit eder. Ornegin "hemen islem yapmaniz gerekiyor", "hesabiniz bloke olacak", "polis/savci olarak ariyorum", "bu kodu kimseyle paylasmayin ama bana soyleyin" gibi ifadeler risk sinyali olarak degerlendirilir. NLP modulu her gorusme icin baglamsal bir risk puani uretir.

## 3. Ses Modulu - Akustik Anomali Analizi

Bu modul, yalnizca konusmanin metnine degil, sesin kendisine de bakar. Amaç, sentetik ses, deepfake ses veya normal insan konusmasindan farkli akustik anomalileri yakalamaktir. CNN-BiLSTM mimarisi gibi derin ogrenme yapilari kullanilarak MFCC, CQCC ve spektrogram ozellikleri analiz edilir. Bu ozellikler sesin frekans yapisini, ton degisimlerini, dogallik seviyesini ve makine tarafindan uretilmis olabilecek izleri ortaya cikarir.

Ses modulu, dolandiricinin yapay ses kullanmasi, ses klonlama denemesi veya robotik/sentetik sesle arama yapmasi durumunda ek risk uretir. Psikoakustik model korumasi sayesinde yalnizca teknik frekans verisi degil, insan kulaginin algilayabilecegi dogallik farklari da dikkate alinir. Bu katman, metin analiziyle birlikte calistigi icin sistem sadece "ne soylendi" sorusuna degil, "bu ses ne kadar guvenilir" sorusuna da cevap verir.

## Fuzyon Katmani - Ses ve Metin Sonuclarinin Birlestirilmesi

Fuzyon katmani, STT, NLP ve ses modullerinden gelen sonuclari tek bir risk puanina cevirir. Slaytta belirtilen agirlik 8:2'dir; yani karar mekanizmasinda metin/sosyal muhendislik analizi daha baskin, ses analizi ise destekleyici sinyal olarak kullanilir. Sonuc 0-100 arasi risk puani olarak hesaplanir.

Risk puani kullaniciya basit bir renk sistemiyle gosterilir: 0-40 arasi guvenli, 40-70 arasi uyari, 70 uzeri engellendi/operator mudahalesi gerektiren yuksek riskli durum olarak yorumlanir. Bu sayede teknik bilgiye sahip olmayan kullanici bile aramanin ne kadar riskli oldugunu aninda anlayabilir.

## Projenin Ozgun Yonleri

Bu proje, Turkceye ozel ilk gercek zamanli cok modlu dolandiricilik tespit sistemlerinden biri olarak konumlanir. Rakip cozumlerin cogu arama bittikten sonra analiz yaparken, bu sistem gorusme sirasinda anlik risk tespiti yapmayi hedefler. Turkce sosyal muhendislik kaliplari, yerel dolandiricilik senaryolari ve Turkce konusma ozellikleri dikkate alinir.

KVKK uyumlu yerinde kurulum secenegi onemli bir farktir. Kullanici verileri buluta zorunlu olarak aktarilmak yerine kurum icinde islenebilir. Ses ve metin fuzyonu sayesinde yalnizca anahtar kelime yakalama degil, daha guclu ve baglamsal bir dolandiricilik tespiti yapilir.

## Altyapi ve Olcek

Sistem mikroservis mimarisi ile tasarlanir. Docker ve Kubernetes kullanimi sayesinde her modul bagimsiz sekilde calistirilabilir, guncellenebilir ve olceklendirilebilir. MLOps ve Kubeflow ile model versiyonlama, drift izleme ve performans takibi yapilir. Bu, modelin zamanla degisen dolandiricilik kaliplarina uyum saglamasini kolaylastirir.

Altyapi hibrit calisacak sekilde planlanir: hassas veriler on-premise ortamda islenebilir, yogun islem ihtiyaci oldugunda bulut kaynaklari destek olarak kullanilabilir. Egitim verisi icin 20-30 saat gercek ve sentetik TTS/LLM kaynakli veri hedeflenir. ISO 27001 sifreleme, KVKK ve BRSA uyumluluk katmanlari sistemin kurumsal kullanima uygun olmasini saglar.

# Slide 2 - Hukuki Uyum, RAG ve Kurumsal Denetim Katmani

## Kisa Aciklama

Bu bolumde projenin finansal ve hukuki uyumluluk tarafi anlatilir. Sistem sadece telefon dolandiriciligini algilamakla kalmaz; ayni zamanda mevzuat, resmi duyurular ve kurum ici belgelerle karsilastirma yaparak hukuki riskleri de degerlendirir. Llama/Mistral tabanli Turk hukukuna ince ayarli LLM, Resmi Gazete API takibi, yerel veri merkezi, AES-256 sifreleme, ISO 27001 uyumlulugu ve RBAC onay akisi birlikte calisir.

## 1. Veri Katmani - Cok Kaynakli Veri Toplama ve Indeksleme

Veri katmani, sistemin bilgi temelini olusturur. Banka ve finans kurumlarinin ice belgeleri, vergi beyannameleri, denetim raporlari, muhasebe kayitlari, sozlesmeler, yapilandirilmis tam metinler ve mevzuat dokumanlari bu katmanda toplanir. Amac, yapay zeka modeline guvenilir ve izlenebilir kaynaklar saglamaktir.

Mevzuat tarafinda VUK, TTK, BRSA, FATF standartlari ve benzeri finansal/vergisel regülasyonlar vektor veritabanina aktarilir. Bu dokumanlar parcalara bolunur, anlam aramasi icin vektorlestirilir ve modelin sorgularda dogru kaynaklara ulasmasi saglanir. Gercek zamanli akis icin resmigazete.gov.tr, hazine.gov.tr ve API/webhook tetikleyicileri takip edilir. Yeni bir mevzuat yayinlandiginda sistem bunu otomatik olarak indeksleyebilir.

ETL pipeline tarafinda Apache Kafka ile veri akisi yonetilir. Gelen belgeler temizlenir, siniflandirilir, bolum/madde duzeyinde indekslenir ve arama icin hazir hale getirilir. Bu sayede model sadece genel cevap uretmez; hangi belge, hangi madde veya hangi mevzuat kismindan yararlandigini da gosterebilir.

## 2. Yapay Zeka Katmani - RAG ve Fine-Tuned LLM Pipeline

Yapay zeka katmani, RAG mimarisi ile calisir. RAG, modelin cevap vermeden once ilgili kaynaklari aramasi ve cevabini bu kaynaklara dayandirmasi anlamina gelir. Kullanici veya sistem bir hukuki/finansal soru sordugunda once sorgu olusturulur, sonra vektor arama yapilir, en ilgili dokuman parcalari bulunur ve LLM bu baglamla yanit uretir. Bu yontem halusinasyon riskini azaltir.

Llama veya Mistral tabanli model Turk hukukuna ve finans terminolojisine gore ince ayarlanir. Model "vergi matrahi", "muafiyet", "beyan", "yukumluluk", "denetim", "uyumsuzluk" gibi kavramlari daha dogru yorumlar. NLP karsilastirma katmani, yeni yasal metinler ile kurum belgeleri arasindaki celiskileri, eksik beyanlari veya uyumsuzluk risklerini tespit eder.

Aciklanabilir yapay zeka katmani, verilen kararlarin gerekcesini gosterir. Her risk veya uyari icin hangi kaynaklara bakildigi, hangi maddenin etkili oldugu ve neden bu sonuca varildigi aciklanir. Bu, insan denetimini kolaylastirir ve sistemin "kara kutu" gibi davranmasini engeller.

## 3. Uygulama Katmani - Proaktif Bildirim, RBAC ve Yerel Uyumluluk

Uygulama katmani, kullaniciya ve kuruma aksiyon alinabilir sonuc verir. Proaktif bildirim sistemi, yeni BRSA/CMB/Resmi Gazete gelismelerini takip eder, mevzuat degisikligini algilar, hangi musterilerin veya sureclerin etkilenebilecegini analiz eder ve gecmis denetim kayitlariyla iliskilendirir.

RBAC ve onay akisi, kurum icindeki yetkilendirmeyi kontrol eder. Her kullanici sadece kendi rolune uygun verilere erisir. IEEE Sandhu 1996 modeline dayali rol bazli erisim yaklasimi sayesinde yetkisiz erisim riski azaltılır. Yetkisiz erisim talebi otomatik olarak yoneticiye iletilir ve denetim gunlugune yazilir.

Kurumsal hafiza katmani, gecmis denetimleri, bugulari, karar gerekcelerini ve tekrar eden sorunlari saklar. Sistem ayni hata tekrarlandiginda gecmisteki ilgili kararlari ve denetim notlarini gosterir. Tam yerel altyapi secenegi sayesinde tum veriler Turkiye veri merkezinde kalabilir. KVKK uyumu icin veri disari aktarilmaz; AES-256 sifreleme ile aktarim ve saklama guvenligi saglanir.

## Projenin Ozgun Yonleri

Bu yontemin en guclu yani, teknik dolandiricilik tespiti ile hukuki/kurumsal uyumlulugu birlestirmesidir. Sistem Turk hukuki LLM yapisi ile TR pazarina ozel calisir. Resmi Gazete API entegrasyonu sayesinde mevzuat degisikliklerini gercek zamanli takip eder. RAG ve aciklanabilir yapay zeka kullanimi, uretilen cevaplarin kaynakli ve denetlenebilir olmasini saglar.

RBAC otomatik onay akisi, yetkisiz erisim ve veri sızıntisi riskini azaltir. Kurumsal hafiza katmani ise denetim surekliligi saglar; kurum ayni hatalari tekrar tekrar yasamak yerine gecmis kararlardan yararlanir. Bu ozellikler projeyi yalnizca bir mobil guvenlik uygulamasi degil, finansal kurumlar icin genisletilebilir bir uyumluluk ve risk yonetimi platformu haline getirir.

## Sunumda Kullanilabilecek Kisa Metin

Bu projede amacimiz, telefon dolandiriciligi ve finansal uyumluluk risklerini tek sistemde ele almaktir. Birinci katmanda uygulama, telefon gorusmesini gercek zamanli analiz eder; konusmayi metne cevirir, sosyal muhendislik kaliplarini tespit eder ve sesin sentetik/deepfake olma ihtimalini olcer. Ikinci katmanda ise kurum belgeleri, mevzuat ve Resmi Gazete verileri RAG destekli hukuk LLM ile analiz edilir. Boylece sistem hem bireysel kullaniciyi anlik dolandiricilik riskine karsi uyarir hem de kurumlara mevzuat uyumu, denetim ve risk yonetimi konusunda aciklanabilir karar destegi saglar.
