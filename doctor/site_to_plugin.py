#!/usr/bin/env python3
"""Generate/repair a CloudStream provider from public HTML using deterministic local rules only."""
import argparse, ipaddress, json, re, socket, sys, urllib.error, urllib.parse, urllib.request
from html.parser import HTMLParser
from pathlib import Path

class Guard(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        url=urllib.parse.urljoin(req.full_url,newurl); validate(url)
        return super().redirect_request(req,fp,code,msg,headers,url)

def validate(url):
    p=urllib.parse.urlsplit(url)
    if p.scheme not in ('http','https') or not p.hostname or p.username or p.password: raise ValueError('Geçerli HTTP(S) URL gerekli.')
    h=p.hostname.lower().rstrip('.')
    if h=='localhost' or h.endswith(('.local','.internal','.localhost')): raise ValueError('Yerel URL reddedildi.')
    try: ips=[ipaddress.ip_address(h)]
    except ValueError: ips=[ipaddress.ip_address(a[4][0].split('%')[0]) for a in socket.getaddrinfo(h,p.port or (443 if p.scheme=='https' else 80),type=socket.SOCK_STREAM)]
    if not ips or any(not x.is_global for x in ips): raise ValueError('Özel IP reddedildi.')
    return p

def fetch(url,ref=''):
    # Use a regular desktop-browser request profile. Some hosts reject the old
    # custom "KayraBuilder" user agent before returning any page HTML.
    headers={
        'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
        'Accept':'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
        'Accept-Language':'tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6',
        'Cache-Control':'no-cache',
        'Pragma':'no-cache',
        'Upgrade-Insecure-Requests':'1',
    }
    if ref:
        headers['Referer']=ref
    else:
        headers['Sec-Fetch-Dest']='document'
        headers['Sec-Fetch-Mode']='navigate'
        headers['Sec-Fetch-Site']='none'
        headers['Sec-Fetch-User']='?1'

    # For a root/site URL only, try the matching www/non-www variant once if
    # the canonical host returns an access error. This is normal host fallback,
    # not a CAPTCHA or authentication bypass.
    candidates=[url]
    try:
        parsed=urllib.parse.urlsplit(url)
        host=parsed.hostname or ''
        if parsed.scheme in ('http','https') and parsed.path in ('','/') and not parsed.query and not parsed.fragment:
            hosts=[host]
            if host.startswith('www.'):
                alternate=host[4:]
            elif host and host.count('.') >= 1:
                alternate='www.'+host
            else:
                alternate=''
            if alternate:
                hosts.append(alternate)
            # Last normal public-page fallback: the site exposes a public film
            # index at /film/. This is a regular page path, not an anti-bot
            # challenge bypass. Do not try private APIs or challenge endpoints.
            for candidate_host in hosts:
                netloc=candidate_host
                if parsed.port:
                    netloc+=f':{parsed.port}'
                listing=urllib.parse.urlunsplit((parsed.scheme,netloc,'/film/','',''))
                if listing not in candidates:
                    candidates.append(listing)
    except Exception:
        pass

    failures=[]
    for index,candidate in enumerate(candidates):
        try:
            validate(candidate)
            with urllib.request.build_opener(Guard).open(urllib.request.Request(candidate,headers=headers),timeout=14) as response:
                return {'url':response.geturl(),'status':response.status,'html':response.read(350000).decode('utf-8','replace')}
        except Exception as e:
            status=getattr(e,'code',None)
            failures.append({'url':candidate,'status':status,'html':'','error':str(e)[:180]})
            # Try a host alias only for an HTTP response likely tied to the
            # requested hostname, rather than masking DNS/TLS/configuration errors.
            if index < len(candidates)-1 and status not in (403,404,406,429) and not (isinstance(status,int) and 500 <= status < 600):
                break
    # Keep an informative HTTP status (e.g. the original 403) if an alias
    # attempt ends in a DNS/TLS failure without an HTTP status.
    best=next((item for item in reversed(failures) if item['status'] is not None), failures[-1] if failures else {'url':url,'status':None,'html':'','error':'No request attempted'})
    return best

class P(HTMLParser):
    def __init__(self,url):
        super().__init__(convert_charrefs=True); self.url=url; self.title=''; self.it=False; self.a=None; self.links=[]; self.images=[]; self.frames=[]; self.media=[]; self.inputs=[]
    def handle_starttag(self,t,attrs):
        d={str(k).lower():(v or '') for k,v in attrs}; t=t.lower()
        if t=='title': self.it=True
        if t=='a' and d.get('href'): self.a={'url':urllib.parse.urljoin(self.url,d['href']),'title':''}
        if t=='img':
            v=next((d[k] for k in ('src','data-src','data-lazy-src','data-original') if d.get(k)),'')
            if v and not v.startswith('data:'): self.images.append({'url':urllib.parse.urljoin(self.url,v),'alt':d.get('alt','')[:100]})
        if t=='iframe' and (d.get('src') or d.get('data-src')): self.frames.append(urllib.parse.urljoin(self.url,d.get('src') or d.get('data-src')))
        if t in ('video','source') and (d.get('src') or d.get('data-src')): self.media.append(urllib.parse.urljoin(self.url,d.get('src') or d.get('data-src')))
        if t=='input' and d.get('name'): self.inputs.append({'name':d['name'],'placeholder':d.get('placeholder','')})
    def handle_endtag(self,t):
        if t.lower()=='title': self.it=False
        if t.lower()=='a' and self.a is not None: self.links.append(self.a); self.a=None
    def handle_data(self,d):
        if self.it: self.title=(self.title+' '+d).strip()[:200]
        if self.a is not None: self.a['title']=(self.a['title']+' '+d).strip()[:100]

def inspect(url):
    page=fetch(url); p=P(page['url']); p.feed(page['html']); links=[]; seen=set()
    for x in p.links:
        u=x['url'].split('#')[0]; q=urllib.parse.urlsplit(u); title=' '.join(x['title'].split()); path=q.path.lower()
        if q.scheme not in ('http','https') or (q.hostname or '').removeprefix('www.')!=(urllib.parse.urlsplit(page['url']).hostname or '').removeprefix('www.') or path in ('','/'): continue
        if any(v in path for v in ('/login','/account','/privacy','/search','/wp-admin')): continue
        if any(v in (path+' '+title.lower()) for v in ('film','movie','dizi','series','watch','izle','episode','bolum','sezon','video')) and u not in seen: seen.add(u); links.append({'url':u,'title':title})
    children=[]
    for x in links[:3]:
        child=fetch(x['url'],page['url']); c=P(child['url']); c.feed(child['html']); children.append({'url':child['url'],'status':child['status'],'title':c.title,'images':c.images[:8],'iframes':c.frames[:8],'media':c.media[:8]})
    sp=urllib.parse.urlsplit(page['url']); origin=urllib.parse.urlunsplit((sp.scheme,sp.netloc,'/','','')).rstrip('/')
    return {'origin':origin,'host':(sp.hostname or '').removeprefix('www.'),'status':page['status'],'title':p.title,'links':links[:20],'images':p.images[:25],'iframes':p.frames[:12],'media':p.media[:12],'search_inputs':p.inputs[:10],'details':children}

def _split_kotlin_args(text):
    parts=[]; start=0; par=brack=brace=0; quote=None; escaped=False
    for i,ch in enumerate(text):
        if quote:
            if escaped: escaped=False
            elif ch=='\\': escaped=True
            elif ch==quote: quote=None
            continue
        if ch in ('"', "'"): quote=ch; continue
        if ch=='(': par+=1
        elif ch==')': par-=1
        elif ch=='[': brack+=1
        elif ch==']': brack-=1
        elif ch=='{': brace+=1
        elif ch=='}': brace-=1
        elif ch==',' and par==0 and brack==0 and brace==0:
            parts.append(text[start:i].strip()); start=i+1
    last=text[start:].strip()
    if last: parts.append(last)
    return parts

def _call_end(text, open_index):
    depth=0; quote=None; escaped=False
    for i in range(open_index,len(text)):
        ch=text[i]
        if quote:
            if escaped: escaped=False
            elif ch=='\\': escaped=True
            elif ch==quote: quote=None
            continue
        if ch in ('"', "'"): quote=ch; continue
        if ch=='(': depth+=1
        elif ch==')':
            depth-=1
            if depth==0: return i
    return -1

def _add_import(src, statement):
    if re.search(r'(?m)^import\s+'+re.escape(statement)+r'\s*$',src): return src
    m=re.search(r'(?m)^package\s+[^\n]+',src)
    if not m: return src
    return src[:m.end()]+'\n\nimport '+statement+src[m.end():]

def _normalize_episode_constructors(src):
    """Rewrite deprecated Episode(...) calls to the current newEpisode builder."""
    parameter_names = ['data', 'name', 'season', 'episode', 'posterUrl', 'score', 'description', 'date', 'runTime']
    search = 0
    while True:
        m = re.search(r'(?<![A-Za-z0-9_])Episode\s*\(', src[search:])
        if not m:
            break
        start = search + m.start()
        op = src.find('(', start)
        end = _call_end(src, op)
        if end < 0:
            break
        args = _split_kotlin_args(src[op + 1:end].strip())
        values = {}
        positional_index = 0
        valid = bool(args)
        for arg in args:
            named = re.match(r'^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+)$', arg, re.S)
            if named:
                key, value = named.group(1), named.group(2).strip()
                if key not in parameter_names:
                    valid = False
                    break
            else:
                if positional_index >= len(parameter_names):
                    valid = False
                    break
                key, value = parameter_names[positional_index], arg.strip()
                positional_index += 1
            if not value or key in values:
                valid = False
                break
            values[key] = value
        data = values.pop('data', None)
        if not valid or not data:
            search = end + 1
            continue
        assignments = '; '.join(f'{key} = {values[key]}' for key in parameter_names[1:] if key in values)
        replacement = f'newEpisode({data})' + (f' {{ {assignments} }}' if assignments else '')
        src = src[:start] + replacement + src[end + 1:]
        search = start + len(replacement)
    return src

def _normalize_cloudstream_api(src):
    # Convert the legacy single-section HomePageResponse constructor to current helper API.
    search=0
    while True:
        m=re.search(r'\bHomePageResponse\s*\(',src[search:])
        if not m: break
        start=search+m.start(); op=src.find('(',start); end=_call_end(src,op)
        if end<0: break
        args=_split_kotlin_args(src[op+1:end].strip())
        replacement=None
        if len(args)==1:
            lm=re.match(r'listOf\s*\((.*)\)\s*$',args[0],re.S)
            if lm:
                rows=_split_kotlin_args(lm.group(1))
                if len(rows)==1:
                    hm=re.match(r'HomePageList\s*\((.*)\)\s*$',rows[0],re.S)
                    if hm:
                        cells=_split_kotlin_args(hm.group(1))
                        if len(cells)>=2:
                            replacement='newHomePageResponse('+cells[0]+', '+cells[1]+', hasNext = false)'
        if replacement:
            src=src[:start]+replacement+src[end+1:]
            search=start+len(replacement)
        else:
            search=end+1

    # Current helper is (title, url, TvType, dataUrl); repair common legacy 3-argument calls.
    search=0
    while True:
        m=re.search(r'\bnewMovieLoadResponse\s*\(',src[search:])
        if not m: break
        start=search+m.start(); op=src.find('(',start); end=_call_end(src,op)
        if end<0: break
        args=_split_kotlin_args(src[op+1:end])
        if len(args)==3 and not re.match(r'TvType\s*\.',args[2]):
            args.insert(2,'TvType.Movie')
            replacement='newMovieLoadResponse('+', '.join(args)+')'
            src=src[:start]+replacement+src[end+1:]
            search=start+len(replacement)
        else:
            search=end+1

    # Current helper takes (title, url, TvType, episodes); repair legacy 3-argument calls.
    search=0
    while True:
        m=re.search(r'\bnewTvSeriesLoadResponse\s*\(',src[search:])
        if not m: break
        start=search+m.start(); op=src.find('(',start); end=_call_end(src,op)
        if end<0: break
        args=_split_kotlin_args(src[op+1:end])
        if len(args)==3 and not re.match(r'TvType\s*\.',args[2]):
            args.insert(2,'TvType.TvSeries')
            replacement='newTvSeriesLoadResponse('+', '.join(args)+')'
            src=src[:start]+replacement+src[end+1:]
            search=start+len(replacement)
        else:
            search=end+1

    src = _normalize_episode_constructors(src)
    if re.search(r'\bnewMovieLoadResponse\s*\(',src):
        src=_add_import(src,'com.lagradost.cloudstream3.newMovieLoadResponse')
    if re.search(r'\bnewTvSeriesLoadResponse\s*\(',src):
        src=_add_import(src,'com.lagradost.cloudstream3.newTvSeriesLoadResponse')
    if re.search(r'\bnewMovieSearchResponse\s*\(',src):
        src=_add_import(src,'com.lagradost.cloudstream3.newMovieSearchResponse')
    if re.search(r'\bExtractorLink\b',src):
        src=_add_import(src,'com.lagradost.cloudstream3.utils.ExtractorLink')
    if re.search(r'\bloadExtractor\s*\(',src):
        src=_add_import(src,'com.lagradost.cloudstream3.utils.loadExtractor')
    if re.search(r'\bnewHomePageResponse\s*\(',src):
        src=_add_import(src,'com.lagradost.cloudstream3.newHomePageResponse')
    if re.search(r'\bnewEpisode\s*\(',src):
        src=_add_import(src,'com.lagradost.cloudstream3.newEpisode')
    return src

def check(src,cls,url):
    if not isinstance(src,str) or 'package com.Kayracs3' not in src: raise RuntimeError('Kotlin package hatalı.')
    src=_normalize_cloudstream_api(src)
    declarations=re.findall(r'\bclass\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*MainAPI\s*\(',src)
    if len(declarations)!=1: raise RuntimeError('Tek bir MainAPI sınıfı bekleniyordu.')
    if declarations[0]!=cls:
        src=re.sub(r'\bclass\s+'+re.escape(declarations[0])+r'(?=\s*:\s*MainAPI\s*\()', 'class '+cls, src, count=1)
    if 'override suspend fun search(' not in src or 'override suspend fun load(' not in src or 'loadLinks(' not in src or url not in src: raise RuntimeError('Zorunlu fonksiyon/mainUrl eksik.')
    if re.search(r'Runtime\s*\.\s*getRuntime|\bProcessBuilder\b|\bDexClassLoader\b|webhook\.site|requestbin',src,re.I): raise RuntimeError('Güvenlik kontrolü reddetti.')
    return src

def save(p,d):
    p=Path(p); p.parent.mkdir(parents=True,exist_ok=True); p.write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')

def _kotlin_literal(value):
    return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$').replace('\r', ' ').replace('\n', ' ') + '"'

def _provider_template(class_name, display_name, origin):
    template = r'''package com.Kayracs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class __CLASS__ : MainAPI() {
    override var mainUrl = __ORIGIN__
    override var name = __DISPLAY__
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf("$mainUrl/" to "Ana Sayfa")

    private val pageHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6"
    )

    private fun fixUrl(raw: String?, base: String = mainUrl): String? {
        val value = raw?.trim()?.replace("\\/", "/")?.replace("&amp;", "&")?.trim('"', '\'')
            ?: return null
        if (value.isBlank() || value == "#" || value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) || value.startsWith("mailto:", true)
        ) return null
        return runCatching {
            when {
                value.startsWith("https://", true) || value.startsWith("http://", true) -> value
                value.startsWith("//") -> "https:$value"
                else -> URI(base).resolve(value).toString()
            }
        }.getOrNull()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun sameSite(url: String): Boolean = runCatching {
        val host = URI(url).host?.lowercase()?.removePrefix("www.") ?: return@runCatching false
        val base = URI(mainUrl).host?.lowercase()?.removePrefix("www.") ?: return@runCatching false
        host == base || host.endsWith(".$base")
    }.getOrDefault(false)

    private fun isContentUrl(url: String): Boolean {
        if (!sameSite(url)) return false
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        if (path.isBlank() || path == "/") return false
        if (Regex("(?i)^/(?:wp-admin|wp-login|login|logout|register|privacy|privacy-policy|terms|contact|about|search|arama|category|kategori|tag|etiket|genre|tur)(?:/|$)").containsMatchIn(path)) return false
        return true
    }

    private fun posterFrom(element: Element, link: Element): String? {
        val image = element.selectFirst("img") ?: link.selectFirst("img")
        val raw = sequenceOf(
            image?.attr("data-src"),
            image?.attr("data-lazy-src"),
            image?.attr("data-original"),
            image?.attr("data-original-src"),
            image?.attr("src")
        ).firstOrNull { !it.isNullOrBlank() && !it.startsWith("data:", true) }
        return fixUrl(raw)
    }

    private fun titleFrom(element: Element, link: Element): String {
        val image = element.selectFirst("img") ?: link.selectFirst("img")
        return sequenceOf(
            element.selectFirst("h1, h2, h3, .title, .film-title, .movie-title")?.text(),
            link.attr("title"),
            image?.attr("alt"),
            link.text(),
            element.attr("title")
        ).mapNotNull { it?.trim() }
            .firstOrNull { it.length >= 2 }
            .orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
            .substringBefore(" | ")
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = if (tagName().equals("a", true)) this else {
            select("a[href]").firstOrNull { anchor ->
                val resolved = fixUrl(anchor.attr("href"))
                resolved != null && isContentUrl(resolved)
            }
        } ?: return null
        val url = fixUrl(link.attr("href")) ?: return null
        if (!isContentUrl(url)) return null

        val title = titleFrom(this, link)
        if (title.length < 2 || title.lowercase() in setOf(
                "ana sayfa", "home", "filmler", "diziler", "kategoriler", "giriş", "giris",
                "kayıt ol", "kayit ol", "iletişim", "iletisim", "hakkımızda", "hakkimizda"
            )
        ) return null

        val allText = "${title} ${url} ${text()}"
        val type = if (Regex("(?i)(dizi|series|season|sezon|episode|bölüm|bolum)").containsMatchIn(allText)) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        val poster = posterFrom(this, link)
        val year = Regex("\\b(?:19|20)\\d{2}\\b").find(allText)?.value?.toIntOrNull()
        return newMovieSearchResponse(title, url, type) {
            posterUrl = poster
            this.year = year
        }
    }

    private fun matchesQuery(query: String, title: String): Boolean {
        val normalizedTitle = title.lowercase()
            .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
            .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        val normalizedQuery = query.lowercase().trim()
            .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
            .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        if (normalizedQuery.isBlank()) return true
        return normalizedTitle.contains(normalizedQuery) ||
            normalizedQuery.split(Regex("\\s+")).filter { it.length > 1 }.all { normalizedTitle.contains(it) }
    }

    private fun parseResults(document: Document, query: String = ""): List<SearchResponse> {
        val cards = document.select(
            "article, .movie-item, .film-item, .video-item, .series-item, .film-card, .movie-card, .post-item, .movie, .film, .series, .card, .thumbnail, .list-item"
        ).mapNotNull { it.toSearchResult() }
        val results = if (cards.isNotEmpty()) cards else {
            document.select("a[href]").mapNotNull { it.toSearchResult() }
        }
        return results.distinctBy { it.url }
            .filter { matchesQuery(query, it.name) }
            .take(100)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            page <= 1 -> request.data
            request.data.endsWith("/") -> request.data.trimEnd('/') + "/page/$page/"
            else -> "$mainUrl/page/$page/"
        }
        val document = runCatching {
            app.get(url, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = parseResults(document)
        return newHomePageResponse(request.name, items, items.isNotEmpty() && page < 50)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?q=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/arama/$encoded/",
            "$mainUrl/?search=$encoded",
            "$mainUrl/?ara=$encoded"
        ).distinct()
        for (url in searchUrls) {
            val document = runCatching {
                app.get(url, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
            }.getOrNull() ?: continue
            val results = parseResults(document, q)
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun parseEpisodeNumbers(value: String): Pair<Int, Int>? {
        val source = value.replace('_', ' ').replace('-', ' ')
        val first = Regex("(?i)(?:sezon|season|s)\\s*(\\d+)\\D{0,40}?(?:bölüm|bolum|episode|ep|e)\\s*(\\d+)")
            .find(source)
        if (first != null) {
            return first.groupValues[1].toIntOrNull()?.let { season ->
                first.groupValues[2].toIntOrNull()?.let { episode -> season to episode }
            }
        }
        val compact = Regex("(?i)\\bs(\\d+)\\s*e(\\d+)\\b").find(value)
        return compact?.let { it.groupValues[1].toIntOrNull()?.let { s -> it.groupValues[2].toIntOrNull()?.let { e -> s to e } } }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url) ?: return null
        val document = runCatching {
            app.get(pageUrl, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
        }.getOrNull() ?: return null

        val title = sequenceOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("h1")?.text(),
            document.title()
        ).mapNotNull { it?.trim() }
            .firstOrNull { it.length > 1 }
            ?.substringBefore(" | ")
            ?.trim()
            ?: return null

        val poster = sequenceOf(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("img[class*=poster]")?.attr("data-src"),
            document.selectFirst("img[class*=poster]")?.attr("src"),
            document.selectFirst("img")?.attr("data-src"),
            document.selectFirst("img")?.attr("src")
        ).firstOrNull { !it.isNullOrBlank() }?.let { fixUrl(it, pageUrl) }

        val description = sequenceOf(
            document.selectFirst("meta[name=description]")?.attr("content"),
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst(".description, .plot, .synopsis, [class*=description]")?.text()
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        val pageText = document.text()
        val year = Regex("\\b(?:19|20)\\d{2}\\b").find(pageText)?.value?.toIntOrNull()
        val ratingText = document.selectFirst(".imdb, .rating, .score, [class*=rating], [class*=imdb]")?.text().orEmpty()
        val rating = Regex("(?<!\\d)(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)(?!\\d)")
            .find(ratingText)?.groupValues?.getOrNull(1)?.replace(',', '.')
        val score = rating?.let { runCatching { Score.from10(it) }.getOrNull() }
        val tags = document.select("a[rel=tag], a[href*=genre], a[href*=tur/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(30)

        val episodes = document.select("a[href]").mapNotNull { anchor ->
            val episodeUrl = fixUrl(anchor.attr("href"), pageUrl) ?: return@mapNotNull null
            if (!sameSite(episodeUrl) || episodeUrl.trimEnd('/') == pageUrl.trimEnd('/')) return@mapNotNull null
            val episodeName = sequenceOf(anchor.text().trim(), anchor.attr("title").trim())
                .firstOrNull { it.isNotBlank() }.orEmpty()
            val descriptor = "$episodeUrl $episodeName"
            if (!Regex("(?i)(bölüm|bolum|episode|/episodes?/|/bolum/|s\\d+\\s*e\\d+)").containsMatchIn(descriptor)) {
                return@mapNotNull null
            }
            val numbers = parseEpisodeNumbers(descriptor)
            newEpisode(episodeUrl) {
                name = episodeName.ifBlank { "Bölüm" }
                season = numbers?.first
                episode = numbers?.second
                posterUrl = poster
            }
        }.distinctBy { it.name + "|" + it.season + "|" + it.episode }

        val isSeries = episodes.isNotEmpty() ||
            Regex("(?i)(/dizi/|/series/|/tv/|season|sezon)").containsMatchIn(pageUrl)
        return if (isSeries) {
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
            }
        } else {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = runCatching {
            app.get(data, headers = pageHeaders, referer = "$mainUrl/", allowRedirects = true).document
        }.getOrNull() ?: return false
        var found = false

        for (track in document.select("track[src], track[data-src]")) {
            val raw = track.attr("src").ifBlank { track.attr("data-src") }
            val subtitleUrl = fixUrl(raw, data) ?: continue
            if (Regex("(?i)\\.(vtt|srt|ass)(\\?|$)").containsMatchIn(subtitleUrl)) {
                val language = track.attr("srclang").ifBlank { track.attr("label") }.ifBlank { "Unknown" }
                subtitleCallback(SubtitleFile(language, subtitleUrl))
            }
        }

        val candidates = linkedSetOf<String>()
        for (element in document.select(
            "iframe[src], iframe[data-src], video[src], video source[src], source[src], a[href*='.m3u8'], a[href*='.mp4']"
        )) {
            val raw = when {
                element.hasAttr("src") -> element.attr("src")
                element.hasAttr("data-src") -> element.attr("data-src")
                else -> element.attr("href")
            }
            fixUrl(raw, data)?.let { candidates.add(it) }
        }

        val html = document.html().replace("\\/", "/").replace("\\u002F", "/", true)
            .replace("\\u0026", "&", true)
        val mediaPattern = Regex("(?i)(?:https?:)?//[^\\s\\\"'<>]+\\.(?:m3u8|mp4)(?:\\?[^\\s\\\"'<>]*)?")
        mediaPattern.findAll(html).forEach { match ->
            fixUrl(match.value, data)?.let { candidates.add(it) }
        }

        for (candidate in candidates) {
            if (Regex("(?i)\\.(m3u8|mp4)(\\?.*)?$").containsMatchIn(candidate)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = candidate,
                        type = if (candidate.substringBefore('?').endsWith(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        referer = data
                        quality = Qualities.Unknown.value
                    }
                )
                found = true
            } else {
                runCatching {
                    loadExtractor(candidate, data, subtitleCallback) { link ->
                        callback(link)
                        found = true
                    }
                }
            }
        }
        return found
    }
}
'''
    return (template
        .replace('__CLASS__', class_name)
        .replace('__ORIGIN__', _kotlin_literal(origin))
        .replace('__DISPLAY__', _kotlin_literal(display_name)))

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--mode',choices=('generate','repair'),required=True)
    ap.add_argument('--url',default='')
    ap.add_argument('--provider-name',default='')
    ap.add_argument('--run-id',default='')
    ap.add_argument('--repo-root',default='.')
    ap.add_argument('--report',required=True)
    ap.add_argument('--metadata',required=True)
    ap.add_argument('--build-log',default='')
    ap.add_argument('--playback-report',default='')
    x=ap.parse_args()
    root=Path(x.repo_root).resolve()
    rp=Path(x.report)
    mp=Path(x.metadata)

    if x.mode == 'generate':
        r=inspect(x.url)
        save(rp,r)
        status=r.get('status')
        if not isinstance(status,int) or not 200 <= status < 300:
            detail=str(r.get('error') or '').strip()
            raise RuntimeError('Site HTML alınamadı; otomatik sağlayıcı üretimi durduruldu. HTTP '+str(status or 'yanıt yok')+'. Site erişim engeli/403 döndürüyorsa, doğrulanabilir sayfa içeriği olmadan işlevsel eklenti üretilemez.'+((' Ayrıntı: '+detail[:180]) if detail else ''))

        known={}
        for b in root.glob('*/build.gradle.kts'):
            for k in b.parent.glob('src/main/kotlin/**/*.kt'):
                m=re.search(r'''(?m)\bmainUrl\b[^=\n]*=\s*["'](https?://[^"']+)["']''',k.read_text(encoding='utf-8',errors='ignore'))
                if m:
                    known.setdefault((urllib.parse.urlsplit(m.group(1)).hostname or '').removeprefix('www.'),[]).append(b.parent.name)
        if r['host'] in known:
            raise RuntimeError('Site için mevcut eklenti var: '+', '.join(known[r['host']]))

        display=x.provider_name.strip() or r['host']
        class_name=''.join(v.title() for v in re.findall(r'[A-Za-z0-9]+',x.provider_name.strip() or r['host'].split('.')[0]))
        class_name=class_name or 'GeneratedSite'
        if class_name[0].isdigit():
            class_name='Site'+class_name
        class_name=class_name[:44]
        folder=root/class_name
        if folder.exists():
            suffix=re.sub(r'[^0-9]','',x.run_id)[-6:] or 'Auto'
            class_name=(class_name[:34]+'Auto'+suffix)[:48]
            folder=root/class_name

        generated=_provider_template(class_name,display,r['origin'])
        generated=check(generated,class_name,r['origin'])
        source_dir=folder/'src/main/kotlin/com/Kayracs3'
        source_dir.mkdir(parents=True,exist_ok=False)
        source_file=source_dir/(class_name+'.kt')
        source_file.write_text(generated.rstrip()+'\n',encoding='utf-8')

        plugin_src=('package com.Kayracs3\n\n'
            'import com.lagradost.cloudstream3.plugins.CloudstreamPlugin\n'
            'import com.lagradost.cloudstream3.plugins.Plugin\n'
            'import android.content.Context\n\n'
            '@CloudstreamPlugin\n'
            'class '+class_name+'Plugin : Plugin() {\n'
            '    override fun load(context: Context) {\n'
            '        registerMainAPI('+class_name+'())\n'
            '    }\n'
            '}\n')
        (source_dir/(class_name+'Plugin.kt')).write_text(plugin_src,encoding='utf-8')

        label=display.replace('"',"'")[:80]
        gradle=('version = 1\n\ncloudstream {\n'
            '    description = "'+label+' otomatik sağlayıcısı"\n'
            '    authors = listOf("Kayracs3")\n'
            '    status = 1\n'
            '    tvTypes = listOf("Movie", "TvSeries")\n'
            '    language = "tr"\n'
            '    iconUrl = "'+r['origin']+'/favicon.ico"\n'
            '}\n\nandroid {\n    buildFeatures {\n        buildConfig = true\n    }\n}\n')
        (folder/'build.gradle.kts').write_text(gradle,encoding='utf-8')

        meta={'module_name':class_name,'display_name':display,'origin':r['origin'],
              'source_path':str(source_file.relative_to(root)).replace('\\','/'),
              'search_query':next((z['title'] for z in r['links'] if len(z['title'])>2),display),
              'summary':'Deterministik HTML/Jsoup şablonuyla üretildi. Video bağlantıları yalnızca sayfada gözlenen kaynaklardan aranır.'}
        save(mp,meta)
        print(json.dumps(meta,ensure_ascii=False))
    else:
        meta=json.loads(mp.read_text(encoding='utf-8'))
        f=root/meta['source_path']
        original=f.read_text(encoding='utf-8',errors='ignore')
        normalized=_normalize_cloudstream_api(original)
        changed=normalized != original
        if changed:
            normalized=check(normalized,meta['module_name'],meta['origin'])
            f.write_text(normalized.rstrip()+'\n',encoding='utf-8')
            meta['repair_summary']='Deterministik CloudStream API normalizasyonu uygulandı; yapay zekâ kullanılmadı.'
        else:
            meta['repair_summary']='Bilinen güvenli API normalizasyonu gerekmedi veya derleme hatası bu kurallarla otomatik düzeltilemiyor; yapay zekâ çağrısı yapılmadı.'
        save(mp,meta)
        print(json.dumps(meta,ensure_ascii=False))

if __name__=='__main__':
    try: main()
    except Exception as e: print('SITE_PLUGIN_BUILDER_ERROR:',str(e),file=sys.stderr); sys.exit(1)
