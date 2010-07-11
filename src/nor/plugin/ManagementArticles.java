/*
 *  Copyright (C) 2010 Junpei Kawamoto
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package nor.plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nor.core.plugin.Plugin;
import nor.core.proxy.filter.FilterRegister;
import nor.core.proxy.filter.ReadonlyPatternMatchingFilter;
import nor.core.proxy.filter.ReadonlyPatternMatchingFilter.MatchingEventListener;
import nor.core.proxy.filter.RequestFilter;
import nor.core.proxy.filter.RequestFilterAdapter;
import nor.core.proxy.filter.ResponseFilter;
import nor.core.proxy.filter.ResponseFilterAdapter;
import nor.core.proxy.filter.StoringToFileFilter;
import nor.http.HeaderName;
import nor.http.HttpHeader;
import nor.http.HttpRequest;
import nor.http.HttpResponse;
import nor.http.Status;
import nor.util.FixedSizeMap;

public class ManagementArticles extends Plugin{

	/*
	 * CiteSeerX
	 * ------------------------------
	 * pdf: http://citeseerx.ist.psu.edu/viewdoc/download?doi={id}
	 * site: http://citeseerx.ist.psu.edu/viewdoc/summary?doi={id}
	 *
	 * Springer
	 * ------------------------------
	 * pdf: http://www.springerlink.com/content/{id}/fulltext.pdf
	 * site: http://www.springerlink.com/content/{id}/
	 *
	 * ieee
	 * ------------------------------
	 * pdf: http://ieeexplore.ieee.org/stampPDF/getPDF.jsp?tp=&arnumber=05272083
	 *
	 * ACM
	 * -------------------------------
	 * pdf: http://delivery.acm.org/.+/.+/{id}/xxxxxx.pdf
	 * site: http://portal.acm.org/citation.cfm?id={id}
	 *
	 */

	private static final Pattern CiteSeerX = Pattern.compile("http://citeseerx\\.ist\\.psu\\.edu/viewdoc/download?.*doi=([^&]+)");
	private static final Pattern SpringerSite = Pattern.compile("springerlink\\.com/content/(\\w+)/");
	private static final Pattern Springer = Pattern.compile("springerlink\\.com/content/(\\w+)/fulltext\\.pdf");

	private static final Pattern ACMSite = Pattern.compile("portal\\.acm\\.org.*citation\\.cfm\\?id=(\\d+)(?:\\.(\\d+))?");
	private static final Pattern ACM = Pattern.compile("delivery\\.acm\\.org/[0-9\\.]+/\\d+/(\\d+)/(.+)\\.pdf");

	private static final Pattern PDF = Pattern.compile("pdf");
	private static final Pattern HTML = Pattern.compile("html");

	private File dir;

	private final Map<String, String> springerTitle = new FixedSizeMap<String, String>(20);
	private final Map<String, String> acmTitle = new FixedSizeMap<String, String>(20);

	@Override
	public void init() {

		if(!this.properties.containsKey("folder")){

			this.properties.setProperty("folder", "./cache/pdf/");

		}

		this.dir = new File(this.properties.getProperty("folder"));
		this.dir.mkdirs();

		StoringToFileFilter.deleteTemplaryFiles(this.dir);

	}

	@Override
	public RequestFilter[] requestFilters() {

		return new RequestFilter[]{

				new RequestFilterAdapter(Springer, PDF){

					@Override
					public void update(final HttpRequest msg, final MatchResult url, final MatchResult cType, final FilterRegister register) {

						final HttpHeader header = msg.getHeader();
						header.remove(HeaderName.Range);
						header.remove(HeaderName. IfRange);

					}

				}

		};

	}

	@Override
	public ResponseFilter[] responseFilters() {

		return new ResponseFilter[]{

				// From CiteSeerX
				new ResponseFilterAdapter(CiteSeerX, PDF){

					@Override
					public void update(final HttpResponse msg, final MatchResult url, final MatchResult cType, final FilterRegister register) {

						if(msg.getStatus() == Status.OK){

							try{

								final URL summary = new URL("http://citeseerx.ist.psu.edu/viewdoc/summary?doi=" + url.group(1));
								final Pattern pat = Pattern.compile("<title>CiteSeerX &#8212; (.*)</title>");
								final String title = this.getTitle(summary, pat);

								if(title != null){

									final File dest = new File(ManagementArticles.this.dir, title + ".pdf");
									register.add(new StoringToFileFilter(dest));

								}

							}catch(final IOException e){

								e.printStackTrace();

							}

						}

					}

					private String getTitle(final URL url, final Pattern pat) throws IOException{

						final InputStream in = ManagementArticles.this.openConnection(url).getInputStream();
						final BufferedReader rin = new BufferedReader(new InputStreamReader(in));
						for(String buf = rin.readLine(); buf != null; buf = rin.readLine()){

							final Matcher m = pat.matcher(buf);
							if(m.find()){

								return m.group(1);

							}

						}

						return null;

					}

				},

				// From Springer
				new ResponseFilterAdapter(SpringerSite, HTML){

					@Override
					public void update(final HttpResponse msg, final MatchResult url, final MatchResult cType, final FilterRegister register) {

						if(msg.getStatus() == Status.OK){

							final ReadonlyPatternMatchingFilter f = new ReadonlyPatternMatchingFilter();
							f.addEventListener("\"ktitle=([^\"]+)\"", new MatchingEventListener(){

								@Override
								public void update(final MatchResult result) {

									ManagementArticles.this.springerTitle.put(url.group(1), result.group(1));

								}});

							register.add(f);

						}

					}

				},

				new ResponseFilterAdapter(Springer, PDF){

					@Override
					public void update(final HttpResponse msg, final MatchResult url, final MatchResult cType, final FilterRegister register) {

						if(msg.getStatus() == Status.OK){

							try{

								final String id = url.group(1);
								final String title = ManagementArticles.this.springerTitle.containsKey(id) ? ManagementArticles.this.springerTitle.get(id) : id;

								final File dest = new File(ManagementArticles.this.dir, title + ".pdf");
								register.add(new StoringToFileFilter(dest));


							}catch(final IOException e){

								e.printStackTrace();

							}

						}

					}

				},

				new ResponseFilterAdapter(ACMSite, HTML){

					@Override
					public void update(final HttpResponse msg, final MatchResult url, final MatchResult cType, final FilterRegister register) {

						if(msg.getStatus() == Status.OK){

							final ReadonlyPatternMatchingFilter f = new ReadonlyPatternMatchingFilter();
							f.addEventListener("<title>(.*)</title>", new MatchingEventListener() {

								@Override
								public void update(final MatchResult result) {

									final String title = result.group(1);

									ManagementArticles.this.acmTitle.put(url.group(1), title);

									final String next = url.group(2);
									if(next != null){

										ManagementArticles.this.acmTitle.put(next, title);

									}

								}

							});
							register.add(f);

						}

					}

				},

				new ResponseFilterAdapter(ACM, PDF) {

					@Override
					public void update(final HttpResponse msg, final MatchResult url, final MatchResult cType, final FilterRegister register) {

						if(msg.getStatus() == Status.OK){

							try{

								final String id = url.group(1);
								final String title = ManagementArticles.this.acmTitle.containsKey(id) ? ManagementArticles.this.acmTitle.get(id) : url.group(2);

								final File dest = new File(ManagementArticles.this.dir, title + ".pdf");
								register.add(new StoringToFileFilter(dest));

							}catch(final IOException e){

								e.printStackTrace();

							}

						}

					}

				}

		};

	}


}
