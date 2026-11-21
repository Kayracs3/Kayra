# KayraLive

KayraLive, CloudStream için genel amaçlı M3U/M3U8 canlı TV sağlayıcısıdır. TheDaddyLive yayınlarını çekmez. Varsayılan olarak iptv-org tarafından yayımlanan Türkçe kanal listesini kullanır; listeye eklenen yayınların bölgesel erişimi ve çalışırlığı değişebilir.

## Android TV kullanımı

Varsayılan olarak [iptv-org Türkçe yayın listesi](https://iptv-org.github.io/iptv/languages/tur.m3u) ve [belesplus listesi](https://raw.githubusercontent.com/sunarproje/support/refs/heads/main/belesplus.m3u) birleştirilir. Ayrıca kullanıcı tarafından belirtilen tekil Sinema 2 HLS adresi ayrı bir kanal olarak listelenir. Her iki listedeki aynı yayın adresleri tek kayıt olarak gösterilir. Yayınların erişilebilirliği ve çalışırlığı kaynaklara ve bölgeye göre değişebilir.

Eklenti ayarlarında birden fazla HTTPS M3U/M3U8 adresi kullanabilirsin: her adresi ayrı satıra yaz. En fazla 10 liste kabul edilir. Bir listenin geçici olarak açılamaması diğer listelerin yüklenmesini durdurmaz. **Temizle** seçeneği varsayılan iki listeyi yeniden etkinleştirir. Yalnızca erişim ve yayın kullanım hakkına sahip olduğun kaynakları kullan.

M3U kayıt biçimi:

```m3u
#EXTM3U
#EXTINF:-1 tvg-name="Channel Name" group-title="News",Channel Name
https://your-authorized-host.example/live/channel.m3u8
```

## Telefon/tablet ayarı

Android telefon veya tablette eklentinin ayar düğmesinden farklı bir HTTPS M3U/M3U8 listesi ekleyebilirsin. Bu ayar cihazda saklanır ve varsayılan depo listesinin önüne geçer. **Temizle** seçeneği varsayılan Kayra listesini yeniden etkinleştirir.

## Güvenlik ve sınırlar

- Kullanıcı ayarlarından eklenen M3U liste adresleri yalnızca HTTPS olabilir. Yerleşik Sinema 2 kanalında ise sadece o tek adrese özel HTTP izni vardır; başka HTTP adreslerine izin verilmez.
- HTTP yayınının sunucusu erişilemezse veya CloudStream/Android ağ güvenliği bağlantıyı engellerse oynatma gerçekleşmez.
- Adında veya grup bilgisinde açıkça 18+ / yetişkin ibaresi bulunan kanallar gizlenir.
- Korumalı API'lere veya oynatıcı doğrulamalarına müdahale etmez.
- Etiket filtresi yanlış etiketlenmiş içerikleri kesin olarak tanımlayamaz.
- Yalnızca kullanma hakkına sahip olduğun yayın listelerini ekle.
