# KayraLive

KayraLive, CloudStream için genel amaçlı M3U/M3U8 canlı TV sağlayıcısıdır. TheDaddyLive yayınlarını çekmez. Varsayılan olarak iptv-org tarafından yayımlanan Türkçe kanal listesini kullanır; listeye eklenen yayınların bölgesel erişimi ve çalışırlığı değişebilir.

## Android TV kullanımı

Varsayılan olarak iptv-org tarafından yayımlanan [Türkçe yayın listesi](https://iptv-org.github.io/iptv/languages/tur.m3u) kullanılır. Kaynak dokümantasyonunda bu listede 181 kanal gösterilir; yayınların bölgesel erişimi, sürekliliği ve çalışırlığı değişebilir. Kaynak, kullanıcıların gönderdiği herkese açık yayın bağlantılarının hak sahipleri tarafından kasıtlı olarak yayımlandığını bildiği ölçüde listelediğini belirtir.

Kendi HTTPS M3U/M3U8 listen varsa eklenti ayarlarından ekleyebilirsin. Yalnızca erişim ve yayın kullanım hakkına sahip olduğun kaynakları kullan.

M3U kayıt biçimi:

```m3u
#EXTM3U
#EXTINF:-1 tvg-name="Channel Name" group-title="News",Channel Name
https://your-authorized-host.example/live/channel.m3u8
```

## Telefon/tablet ayarı

Android telefon veya tablette eklentinin ayar düğmesinden farklı bir HTTPS M3U/M3U8 listesi ekleyebilirsin. Bu ayar cihazda saklanır ve varsayılan depo listesinin önüne geçer. **Temizle** seçeneği varsayılan Kayra listesini yeniden etkinleştirir.

## Güvenlik ve sınırlar

- Yalnızca HTTPS kaynakları kabul edilir.
- Adında veya grup bilgisinde açıkça 18+ / yetişkin ibaresi bulunan kanallar gizlenir.
- Korumalı API'lere veya oynatıcı doğrulamalarına müdahale etmez.
- Etiket filtresi yanlış etiketlenmiş içerikleri kesin olarak tanımlayamaz.
- Yalnızca kullanma hakkına sahip olduğun yayın listelerini ekle.
