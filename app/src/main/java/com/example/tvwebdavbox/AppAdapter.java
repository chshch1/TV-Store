package com.example.tvwebdavbox;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 已安装应用列表适配器，支持多选。 */
public class AppAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final Context context;
    private final List<InstalledApp> items = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();

    public AppAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    public void submit(List<InstalledApp> data, boolean keepSelection) {
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        if (!keepSelection) {
            selected.clear();
        }
        notifyDataSetChanged();
    }

    public InstalledApp getItemAt(int position) {
        if (position < 0 || position >= items.size()) {
            return null;
        }
        return items.get(position);
    }

    public boolean isSelected(int position) {
        InstalledApp item = getItemAt(position);
        return item != null && selected.contains(item.packageName);
    }

    public void toggle(int position) {
        InstalledApp item = getItemAt(position);
        if (item == null) {
            return;
        }
        if (!selected.remove(item.packageName)) {
            selected.add(item.packageName);
        }
        notifyDataSetChanged();
    }

    public void selectAll() {
        for (InstalledApp item : items) {
            selected.add(item.packageName);
        }
        notifyDataSetChanged();
    }

    public void clearSelection() {
        selected.clear();
        notifyDataSetChanged();
    }

    public int selectedCount() {
        return selected.size();
    }

    public List<InstalledApp> selectedItems() {
        List<InstalledApp> result = new ArrayList<>();
        for (InstalledApp item : items) {
            if (selected.contains(item.packageName)) {
                result.add(item);
            }
        }
        return result;
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
            convertView = inflater.inflate(R.layout.row_app, parent, false);
            holder = new ViewHolder();
            holder.check = convertView.findViewById(R.id.app_check);
            holder.icon = convertView.findViewById(R.id.app_icon);
            holder.name = convertView.findViewById(R.id.app_name);
            holder.meta = convertView.findViewById(R.id.app_meta);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        InstalledApp item = items.get(position);
        holder.name.setText(item.label);
        holder.meta.setText(item.metaText());
        holder.check.setChecked(selected.contains(item.packageName));
        if (item.icon != null) {
            holder.icon.setImageDrawable(item.icon);
        } else {
            holder.icon.setImageResource(R.drawable.ic_file);
        }
        // 选中行高亮统一由 ListView 的 selector 负责
        convertView.setSelected(selected.contains(item.packageName));
        return convertView;
    }

    private static final class ViewHolder {
        CheckBox check;
        ImageView icon;
        TextView name;
        TextView meta;
    }
}
