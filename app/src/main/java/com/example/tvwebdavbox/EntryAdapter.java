package com.example.tvwebdavbox;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 电视端文件列表适配器：大字号、高对比度，方向键选中即可。 */
public class EntryAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<RowItem> items = new ArrayList<>();

    public EntryAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    public void submit(List<RowItem> data) {
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        notifyDataSetChanged();
    }

    public RowItem getItemAt(int position) {
        if (position < 0 || position >= items.size()) {
            return null;
        }
        return items.get(position);
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.row_entry, parent, false);
            holder = new ViewHolder();
            holder.icon = convertView.findViewById(R.id.row_icon);
            holder.name = convertView.findViewById(R.id.row_name);
            holder.meta = convertView.findViewById(R.id.row_meta);
            holder.badge = convertView.findViewById(R.id.row_badge);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        RowItem item = items.get(position);
        holder.name.setText(item.name);
        holder.meta.setText(item.subtitle);
        holder.meta.setVisibility(item.subtitle == null || item.subtitle.isEmpty()
                ? View.GONE : View.VISIBLE);
        holder.icon.setImageResource(item.directory ? R.drawable.ic_folder : R.drawable.ic_file);

        if (item.badge == null || item.badge.isEmpty()) {
            holder.badge.setVisibility(View.GONE);
        } else {
            holder.badge.setVisibility(View.VISIBLE);
            holder.badge.setText(item.badge);
            holder.badge.setTextColor(item.badgeColor == 0
                    ? 0xFF9AA5B1 : item.badgeColor);
        }
        return convertView;
    }

    private static final class ViewHolder {
        ImageView icon;
        TextView name;
        TextView meta;
        TextView badge;
    }
}
