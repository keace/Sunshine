package ua.kyslytsia.sunshine;

import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Time;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ForecastFragment extends Fragment {

    ArrayAdapter<String> mForecastAdapter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Add this line in order for this fragment to handle menu events.
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.forecast_fragment, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        //Handle item selection
        switch (item.getItemId()) {
            case R.id.action_refresh:
                FetchWeatherTask weatherTask = new FetchWeatherTask();
                weatherTask.execute("94043");
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                     Bundle savedInstanceState) {
                View rootView = inflater.inflate(R.layout.fragment_main, container, false);

                String[] data = {
                        "Monday 6/23 - Sunny - 31/17",
                        "Tuesday 6/24 - Foggy - 21/8",
                        "Wednesday 6/25 - Cloudy - 22/17",
                        "Thursday 6/26 - Rainy - 18/11",
                        "Friday 6/27 - Foggy - 21/10",
                        "Saturday 6/28 - TRAPPED IN WEATHERSTATION - 23/18",
                        "Sunday 6/29 - Sunny - 20/7"
                };

                List<String> weekForecast = new ArrayList<>(Arrays.asList(data));
                mForecastAdapter = new ArrayAdapter<>(getContext(), R.layout.list_item_forecast, R.id.list_item_forecast_textview, weekForecast);

                //Get a reference to ListView and attach ArrayAdapter
                ListView listView = (ListView) rootView.findViewById(R.id.listview_forecast);
                listView.setAdapter(mForecastAdapter);

                return rootView;
            }

    private String[] getWeatherDataFromJson(String forecastJsonStr, int numDays) throws JSONException {

        final String LOG_TAG = this.getClass().getSimpleName();

        //Names of JSON objects that need to be extracted
        final String OWM_LIST = "list";
        final String OWM_WEATHER = "weather";
        final String OWM_TEMPERATURE = "temp";
        final String OWM_MAX_TEMPERATURE = "max";
        final String OWM_MIN_TEMPERATURE = "min";
        final String OWM_DESCRIPTION = "main";

        JSONObject forecastJson = new JSONObject(forecastJsonStr);
        JSONArray weatherArray = forecastJson.getJSONArray(OWM_LIST);

        // OWM returns daily forecasts based upon the local time of the city that is being
        // asked for, which means that we need to know the GMT offset to translate this data
        // properly.

        // Since this data is also sent in-order and the first day is always the
        // current day, we're going to take advantage of that to get a nice
        // normalized UTC date for all of our weather.

        android.text.format.Time dayTime = new android.text.format.Time();
        dayTime.setToNow();

        // we start at the day returned by local time. Otherwise this is a mess.
        int julianStartDay = android.text.format.Time.getJulianDay(System.currentTimeMillis(), dayTime.gmtoff);

        // now we work exclusively in UTC
        dayTime = new android.text.format.Time();

        String[] resultStrs = new String[numDays];

        for (int i = 0; i < weatherArray.length(); i++) {
            //For now, using the format "Day, description, hi/low"
            String day;
            String description;
            String hiAndLow;

            //Get the JSON object representing the day
            JSONObject dayForecast = weatherArray.getJSONObject(i);

            //Converting time to readable
            long dateTime;
            dateTime = dayTime.setJulianDay(julianStartDay+i);
            day = getReadableDateString(dateTime);

            // description is in a child array called "weather", which is 1 element long.
            JSONObject weatherObject = dayForecast.getJSONArray(OWM_WEATHER).getJSONObject(0);
            description = weatherObject.getString(OWM_DESCRIPTION);

            JSONObject temperatureObject = dayForecast.getJSONObject(OWM_TEMPERATURE);
            double high = temperatureObject.getDouble(OWM_MAX_TEMPERATURE);
            double low = temperatureObject.getDouble(OWM_MIN_TEMPERATURE);

            hiAndLow = formatHighLows(high, low);
            resultStrs[i] = day + " - " + description + " - " + hiAndLow;
        }

        for (String s:resultStrs) {
             Log.v(LOG_TAG, "Forecast entry: " + s);
        }
        return resultStrs;
    }

    private String getReadableDateString(long time) {
        // Because the API returns a unix timestamp (measured in seconds),
        // it must be converted to milliseconds in order to be converted to valid date.
        SimpleDateFormat shortenedDateFormat = new SimpleDateFormat("EEE, MMM dd");
        return shortenedDateFormat.format(time);
    }

    private String formatHighLows(double high, double low) {
        long roundedHigh = Math.round(high);
        long roundedLow = Math.round(low);

        String highLowStr = roundedHigh + "/" + roundedLow;
        return highLowStr;
    }
    public class FetchWeatherTask extends AsyncTask<String, Void, String[]> {

        private final String LOG_TAG = FetchWeatherTask.class.getSimpleName();
        //private String zipCode = "61101";

        @Override
        protected String[] doInBackground(String... params) {
            String[] weatherListResult = null;

            if (params.length == 0) {
                return null;
            }

            // These two need to be declared outside the try/catch
            // so that they can be closed in the finally block.
            HttpURLConnection urlConnection = null;
            BufferedReader reader = null;

            // Will contain the raw JSON response as a string.
            String forecastJsonStr = null;

            try {
                // Construct the URL for the OpenWeatherMap query
                // Possible parameters are avaiable at OWM's forecast API page, at
                // http://openweathermap.org/API#forecast

                final String FORECAST_BASE_URL = "http://api.openweathermap.org/data/2.5/forecast/daily?";
                final String CITY_PARAM = "q";
                final String MODE_PARAM = "mode";
                final String UNITS_PARAM = "units";
                final String DAYSCOUNT_PARAM = "cnt";
                final String APPID_PARAM = "APPID";

                final String MODE_JSON = "json";
                final String UNITS_METRIC = "metric";
                final int DAYS_7 = 7;

                Uri builtUri = Uri.parse(FORECAST_BASE_URL).buildUpon()
                .appendQueryParameter(CITY_PARAM, params[0])
                .appendQueryParameter(MODE_PARAM, MODE_JSON)
                .appendQueryParameter(UNITS_PARAM, UNITS_METRIC)
                .appendQueryParameter(DAYSCOUNT_PARAM, String.valueOf(DAYS_7))
                .appendQueryParameter(APPID_PARAM, Constants.OPEN_WEATHER_APP_ID)
                .build();

                URL url = new URL(builtUri.toString());

                Log.v(LOG_TAG, "Built URI: " + builtUri.toString());

                // Create the request to OpenWeatherMap, and open the connection
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("GET");
                urlConnection.connect();

                // Read the input stream into a String
                InputStream inputStream = urlConnection.getInputStream();
                StringBuffer buffer = new StringBuffer();
                if (inputStream == null) {
                    // Nothing to do.
                    return null;
                }
                reader = new BufferedReader(new InputStreamReader(inputStream));

                String line;
                while ((line = reader.readLine()) != null) {
                    // Since it's JSON, adding a newline isn't necessary (it won't affect parsing)
                    // But it does make debugging a *lot* easier if you print out the completed
                    // buffer for debugging.
                    buffer.append(line + "\n");
                }

                if (buffer.length() == 0) {
                    // Stream was empty.  No point in parsing.
                    return null;
                }
                forecastJsonStr = buffer.toString();
                Log.v(LOG_TAG, "Json output: " + forecastJsonStr);

            } catch (IOException e) {
                Log.e(LOG_TAG, "Error ", e);
                // If the code didn't successfully get the weather data, there's no point in attemping
                // to parse it.
                return null;
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (final IOException e) {
                        Log.e(LOG_TAG, "Error closing stream", e);
                    }
                }
            }
            try {
                weatherListResult = getWeatherDataFromJson(forecastJsonStr, 7);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return weatherListResult;
            }

        @Override
        protected void onPostExecute(String[] result) {
            //super.onPostExecute(strings);
            if (result != null) {
                mForecastAdapter.clear();
                for (String dayForecastStr : result) {
                    mForecastAdapter.add(dayForecastStr);
                }
            }
        }
    }
}